package com.mysc.mydoc.service;

import java.util.UUID;

public record SearchHit(UUID documentId, String title, String headingPath, String snippet, double score) {}
