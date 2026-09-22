package com.relyon.economizaai.service.attribution;

import com.relyon.economizaai.dto.request.AttributionInfo;
import com.relyon.economizaai.dto.request.VisitBeaconRequest;
import com.relyon.economizaai.model.Visit;
import com.relyon.economizaai.model.enums.AcquisitionChannel;
import com.relyon.economizaai.repository.VisitRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class VisitServiceTest {

    @Mock private VisitRepository visitRepository;
    // Real resolver — channel derivation is the behavior under test.
    private final AttributionResolver attributionResolver = new AttributionResolver();

    private VisitService service() {
        return new VisitService(visitRepository, attributionResolver);
    }

    private AttributionInfo paidInstagram() {
        return new AttributionInfo("instagram", "paid", "setembro", null, null, "fbclid123", null, "/promo");
    }

    @Test
    void recordsVisitWithDerivedChannelAndHashedIp() {
        var request = new VisitBeaconRequest("anon-abc", "WEB", paidInstagram());

        service().record(request, "203.0.113.7", "Mozilla/5.0");

        var captor = ArgumentCaptor.forClass(Visit.class);
        verify(visitRepository).save(captor.capture());
        var visit = captor.getValue();
        assertEquals("anon-abc", visit.getAnonId());
        assertEquals(AcquisitionChannel.INSTAGRAM_PAID, visit.getAcquisitionChannel());
        assertEquals("setembro", visit.getUtmCampaign());
        assertEquals("WEB", visit.getPlatform());
        assertNotNull(visit.getIpHash());
        // one-way hash — never the raw ip
        assertEquals(64, visit.getIpHash().length());
    }

    @Test
    void emptyAttributionIsUnknownChannel() {
        var request = new VisitBeaconRequest("anon-1", "WEB", null);

        service().record(request, "203.0.113.7", "ua");

        var captor = ArgumentCaptor.forClass(Visit.class);
        verify(visitRepository).save(captor.capture());
        assertEquals(AcquisitionChannel.UNKNOWN, captor.getValue().getAcquisitionChannel());
    }

    @Test
    void missingAnonIdIsIgnored() {
        service().record(new VisitBeaconRequest("  ", "WEB", paidInstagram()), "1.2.3.4", "ua");
        service().record(null, "1.2.3.4", "ua");

        verify(visitRepository, never()).save(any());
    }

    @Test
    void nullIpProducesNullHashNotCrash() {
        var request = new VisitBeaconRequest("anon-2", "WEB", paidInstagram());

        service().record(request, null, "ua");

        var captor = ArgumentCaptor.forClass(Visit.class);
        verify(visitRepository).save(captor.capture());
        assertNull(captor.getValue().getIpHash());
    }
}
