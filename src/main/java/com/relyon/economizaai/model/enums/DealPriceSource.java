package com.relyon.economizaai.model.enums;

/**
 * Where the price quoted by a deal came from. {@code OWN_PURCHASE}: the supporting observation
 * is the household's own purchase at that chain ("você pagou R$ X no mercado A em DD/MM").
 * {@code COMMUNITY}: it was contributed by another household ("visto por R$ X ...").
 */
public enum DealPriceSource {
    OWN_PURCHASE,
    COMMUNITY
}
