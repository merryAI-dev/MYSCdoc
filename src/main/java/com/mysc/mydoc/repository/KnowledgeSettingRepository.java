package com.mysc.mydoc.repository;

import com.mysc.mydoc.domain.KnowledgeSetting;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeSettingRepository extends JpaRepository<KnowledgeSetting, Integer> {
}
