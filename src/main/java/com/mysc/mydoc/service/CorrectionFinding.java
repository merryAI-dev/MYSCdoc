package com.mysc.mydoc.service;

public record CorrectionFinding(String category, Integer blockPosition, String original, String suggestion, String reason) {}
