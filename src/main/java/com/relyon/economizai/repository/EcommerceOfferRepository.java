package com.relyon.economizai.repository;

import com.relyon.economizai.model.EcommerceOffer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface EcommerceOfferRepository extends JpaRepository<EcommerceOffer, UUID> {

    /** Active offers for a barcode — the match set for a scanned item. */
    List<EcommerceOffer> findByEanAndActiveTrue(String ean);

    /** Admin curation list, newest edits first. */
    Page<EcommerceOffer> findAllByOrderByUpdatedAtDesc(Pageable pageable);

    /** Admin curation list filtered by EAN. */
    Page<EcommerceOffer> findByEanOrderByUpdatedAtDesc(String ean, Pageable pageable);
}
