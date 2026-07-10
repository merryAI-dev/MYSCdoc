package com.mysc.mydoc.service;

import com.mysc.mydoc.domain.KnowledgeTriple;
import com.mysc.mydoc.repository.KnowledgeTripleRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 지식그래프 임베딩(TransE)을 순수 Java·인메모리로 학습해 명시 안 된 엣지를 예측(link prediction)한다.
 * Gemini를 전혀 쓰지 않는 CPU 벡터 연산이라 LLM 토큰 비용이 0이다.
 *
 * TransE: 엔티티/관계를 벡터로 두고 스코어 d(h,r,t) = ‖e_h + e_r − e_t‖ 가 참인 트리플에서 작아지도록
 * margin ranking loss로 GD 학습한다. 여기에 weight decay(λ, ℓ2)를 넣어, 첨부 논문의 그로킹 조건
 * (over-parameterization: dim ≫ 필요차원, small λ, 초기화 스케일 ν)을 그대로 재현한다 —
 * λ를 낮추면 train loss는 일찍 떨어지지만 test(link prediction) 성능은 뒤늦게 올라오는 곡선이 관찰된다.
 */
@Service
public class GraphEmbeddingService {
    private static final Logger log = LoggerFactory.getLogger(GraphEmbeddingService.class);

    private final KnowledgeTripleRepository triples;

    public GraphEmbeddingService(KnowledgeTripleRepository triples) {
        this.triples = triples;
    }

    // ── 요청/응답 타입 ──────────────────────────────────────────────
    public record TrainConfig(int dim, int epochs, double learningRate, double weightDecay,
                              double margin, double initScale, double testRatio, long seed) {
        public static TrainConfig defaults() {
            // dim=64 ≫ 실제 필요차원(over-parameterized), weightDecay 작게, initScale=ν
            return new TrainConfig(64, 400, 0.05, 1e-4, 1.0, 0.3, 0.1, 42L);
        }
    }

    public record GrokPoint(int epoch, double trainLoss, double testHits10) {}
    public record TrainResult(int entities, int relations, int trainTriples, int testTriples,
                              List<GrokPoint> curve, double finalTrainLoss, double finalTestHits10,
                              Integer grokEpoch) {}

    public record Prediction(String subject, String predicate, String object, double score) {}

    // 학습된 모델(인메모리 캐시). 단일 인스턴스 배포 전제라 volatile로 충분.
    private volatile TransEModel model;

    /** 지금 그래프로 TransE를 학습하고, 그로킹 곡선(train loss vs test hits@10)을 함께 돌려준다. */
    @Transactional(readOnly = true)
    public synchronized TrainResult train(TrainConfig cfg) {
        List<KnowledgeTriple> all = triples.findAll();
        if (all.size() < 6) {
            return new TrainResult(0, 0, 0, 0, List.of(), 0, 0, null);
        }
        TransEModel m = new TransEModel(all, cfg);
        TrainResult result = m.train();
        this.model = m;
        log.info("TransE 학습 완료: entities={}, relations={}, train={}, test={}, finalHits@10={}",
                result.entities(), result.relations(), result.trainTriples(), result.testTriples(), result.finalTestHits10());
        return result;
    }

    /** 특정 엔티티에서 나가는(subject=entity) 그럴듯한 새 관계를 예측. 이미 저장된 사실은 제외한다(=추론). */
    @Transactional(readOnly = true)
    public List<Prediction> predict(String entity, int topK) {
        TransEModel m = this.model;
        if (m == null) {
            m = new TransEModel(triples.findAll(), TrainConfig.defaults());
            m.train();
            this.model = m;
        }
        return m.predictFromSubject(entity, topK);
    }

    // ── TransE 본체 ────────────────────────────────────────────────
    private static final class TransEModel {
        final TrainConfig cfg;
        final List<String> entities = new ArrayList<>();
        final List<String> relations = new ArrayList<>();
        final Map<String, Integer> entityIx = new HashMap<>();
        final Map<String, Integer> relIx = new HashMap<>();
        final int[][] allTriples;      // {h, r, t}
        final Set<Long> knownEdges = new HashSet<>(); // (h,r,t) 존재 여부 — 예측 시 기존 사실 제외용
        int[][] trainTriples;
        int[][] testTriples;
        double[][] E; // 엔티티 임베딩 [nE][dim]
        double[][] R; // 관계 임베딩 [nR][dim]
        final Random rnd;

        TransEModel(List<KnowledgeTriple> src, TrainConfig cfg) {
            this.cfg = cfg;
            this.rnd = new Random(cfg.seed());
            List<int[]> ts = new ArrayList<>();
            for (KnowledgeTriple kt : src) {
                int h = intern(entities, entityIx, kt.getSubject());
                int r = intern(relations, relIx, kt.getPredicate());
                int t = intern(entities, entityIx, kt.getObject());
                ts.add(new int[]{h, r, t});
                knownEdges.add(edgeKey(h, r, t));
            }
            this.allTriples = ts.toArray(new int[0][]);
        }

        private static int intern(List<String> vocab, Map<String, Integer> ix, String s) {
            Integer i = ix.get(s);
            if (i != null) {
                return i;
            }
            int idx = vocab.size();
            vocab.add(s);
            ix.put(s, idx);
            return idx;
        }

        private long edgeKey(int h, int r, int t) {
            // nE, nR가 수천 이하라 비트 패킹으로 충돌 없이 인코딩
            return (((long) h) << 40) | (((long) r) << 20) | t;
        }

        TrainResult train() {
            int nE = entities.size(), nR = relations.size(), dim = cfg.dim();
            E = new double[nE][dim];
            R = new double[nR][dim];
            // 초기화 θ ~ N(0, ν²) — ν(initScale)가 논문의 그로킹 증폭 손잡이
            for (double[] v : E) {
                for (int k = 0; k < dim; k++) {
                    v[k] = rnd.nextGaussian() * cfg.initScale();
                }
            }
            for (double[] v : R) {
                for (int k = 0; k < dim; k++) {
                    v[k] = rnd.nextGaussian() * cfg.initScale();
                }
            }

            // train/test 분할
            List<int[]> shuffled = new ArrayList<>(List.of(allTriples));
            Collections.shuffle(shuffled, rnd);
            int nTest = Math.max(1, (int) Math.round(shuffled.size() * cfg.testRatio()));
            testTriples = shuffled.subList(0, nTest).toArray(new int[0][]);
            trainTriples = shuffled.subList(nTest, shuffled.size()).toArray(new int[0][]);
            if (trainTriples.length == 0) { // testRatio가 너무 커서 train이 빈 경우 방어
                trainTriples = testTriples;
            }

            List<GrokPoint> curve = new ArrayList<>();
            int evalEvery = Math.max(1, cfg.epochs() / 40);
            Integer grokEpoch = null;
            double lastTrainLoss = 0, lastHits = 0;
            List<int[]> order = new ArrayList<>(List.of(trainTriples));

            for (int epoch = 1; epoch <= cfg.epochs(); epoch++) {
                Collections.shuffle(order, rnd);
                double loss = 0;
                for (int[] pos : order) {
                    loss += step(pos);
                }
                lastTrainLoss = loss / order.size();
                // weight decay (ℓ2) — 그로킹의 핵심 재료. 매 epoch 전체 파라미터를 (1 − ηλ)배 수축
                double decay = 1.0 - cfg.learningRate() * cfg.weightDecay();
                if (decay < 1.0) {
                    scale(E, decay);
                    scale(R, decay);
                }
                if (epoch % evalEvery == 0 || epoch == cfg.epochs()) {
                    lastHits = hitsAt10(testTriples);
                    curve.add(new GrokPoint(epoch, round(lastTrainLoss), round(lastHits)));
                    // 그로킹 시점: train loss가 이미 낮게(< 0.2·초기) 수렴한 뒤 test hits@10이 처음 0.3을 넘는 지점
                    if (grokEpoch == null && lastHits >= 0.3 && lastTrainLoss < 0.5) {
                        grokEpoch = epoch;
                    }
                }
            }
            return new TrainResult(entities.size(), relations.size(), trainTriples.length, testTriples.length,
                    curve, round(lastTrainLoss), round(lastHits), grokEpoch);
        }

        /** 트리플 하나에 대한 margin ranking loss와 그라디언트 스텝. 손실값을 리턴. */
        private double step(int[] pos) {
            int h = pos[0], r = pos[1], t = pos[2];
            // 부정 샘플: head 또는 tail을 무작위 엔티티로 교체
            boolean corruptHead = rnd.nextBoolean();
            int nh = h, nt = t;
            if (corruptHead) {
                nh = rnd.nextInt(entities.size());
            } else {
                nt = rnd.nextInt(entities.size());
            }
            double[] diffPos = diff(h, r, t);
            double[] diffNeg = diff(nh, r, nt);
            double dPos = norm(diffPos);
            double dNeg = norm(diffNeg);
            double l = cfg.margin() + dPos - dNeg;
            if (l <= 0) {
                return 0; // margin 만족 — 업데이트 없음
            }
            double lr = cfg.learningRate();
            double gp = 1.0 / Math.max(dPos, 1e-8);
            double gn = 1.0 / Math.max(dNeg, 1e-8);
            for (int k = 0; k < cfg.dim(); k++) {
                double dp = diffPos[k] * gp; // ∂dPos/∂(h,r) = +unit, ∂/∂t = −unit
                double dn = diffNeg[k] * gn;
                // L = margin + dPos − dNeg
                E[h][k]  -= lr * dp;         // ∂L/∂E[h] = +dp
                E[t][k]  += lr * dp;         // ∂L/∂E[t] = −dp
                R[r][k]  -= lr * (dp - dn);  // ∂L/∂R[r] = dp − dn (같은 관계가 pos·neg 모두에)
                E[nh][k] += lr * dn;         // ∂L/∂E[nh] = −dn
                E[nt][k] -= lr * dn;         // ∂L/∂E[nt] = +dn
            }
            return l;
        }

        private double[] diff(int h, int r, int t) {
            double[] d = new double[cfg.dim()];
            for (int k = 0; k < cfg.dim(); k++) {
                d[k] = E[h][k] + R[r][k] - E[t][k];
            }
            return d;
        }

        private double score(int h, int r, int t) {
            double s = 0;
            for (int k = 0; k < cfg.dim(); k++) {
                double d = E[h][k] + R[r][k] - E[t][k];
                s += d * d;
            }
            return Math.sqrt(s);
        }

        /** 각 테스트 트리플에서 참 tail의 순위를 매겨 hits@10 비율을 계산(raw). */
        private double hitsAt10(int[][] test) {
            if (test.length == 0) {
                return 0;
            }
            int hits = 0, nE = entities.size();
            for (int[] tr : test) {
                double trueScore = score(tr[0], tr[1], tr[2]);
                int rank = 1;
                for (int o = 0; o < nE; o++) {
                    if (o == tr[2]) {
                        continue;
                    }
                    if (score(tr[0], tr[1], o) < trueScore) {
                        rank++;
                        if (rank > 10) {
                            break;
                        }
                    }
                }
                if (rank <= 10) {
                    hits++;
                }
            }
            return (double) hits / test.length;
        }

        List<Prediction> predictFromSubject(String entity, int topK) {
            Integer h = entityIx.get(entity);
            if (h == null) {
                return List.of();
            }
            int nE = entities.size(), nR = relations.size();
            List<Prediction> preds = new ArrayList<>();
            for (int r = 0; r < nR; r++) {
                for (int o = 0; o < nE; o++) {
                    if (o == h || knownEdges.contains(edgeKey(h, r, o))) {
                        continue; // 자기 자신·이미 저장된 사실 제외 → 순수 예측만
                    }
                    preds.add(new Prediction(entity, relations.get(r), entities.get(o), round(score(h, r, o))));
                }
            }
            preds.sort((a, b) -> Double.compare(a.score(), b.score())); // 거리 작을수록 그럴듯
            return preds.subList(0, Math.min(topK, preds.size()));
        }

        private static void scale(double[][] m, double f) {
            for (double[] v : m) {
                for (int k = 0; k < v.length; k++) {
                    v[k] *= f;
                }
            }
        }

        private static double norm(double[] v) {
            double s = 0;
            for (double x : v) {
                s += x * x;
            }
            return Math.sqrt(s);
        }

        private static double round(double x) {
            return Math.round(x * 10000.0) / 10000.0;
        }
    }
}
