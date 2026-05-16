package com.example.urlanalytics.repository;

import com.example.urlanalytics.entity.ClickEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ClickEventRepository extends JpaRepository<ClickEvent, Long> {
    long countByShortUrlId(Long id);
}
