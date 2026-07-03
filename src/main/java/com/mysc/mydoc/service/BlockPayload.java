package com.mysc.mydoc.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.mysc.mydoc.domain.BlockType;
import com.mysc.mydoc.domain.SourceType;

public record BlockPayload(BlockType type, JsonNode content, SourceType sourceType, String sourceUrl, String sourceRef) {}
