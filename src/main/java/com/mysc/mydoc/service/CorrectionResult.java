package com.mysc.mydoc.service;

import java.util.List;

public record CorrectionResult(Integer score, List<CorrectionFinding> findings) {}
