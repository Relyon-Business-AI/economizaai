package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.ReceiptRawHtml;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ReceiptRawHtmlRepository extends JpaRepository<ReceiptRawHtml, UUID> {
}
