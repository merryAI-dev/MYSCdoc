package com.mysc.mydoc.service;

public record CorrectionFinding(String category, int blockPosition, String original, String suggestion, String reason) {}
