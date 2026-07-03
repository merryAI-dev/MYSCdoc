package com.mysc.mydoc.service;

import java.util.List;

public record CorrectionResult(int score, List<CorrectionFinding> findings) {}
