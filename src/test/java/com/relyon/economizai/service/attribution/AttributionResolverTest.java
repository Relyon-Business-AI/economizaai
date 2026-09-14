package com.relyon.economizai.service.attribution;

import com.relyon.economizai.dto.request.AttributionInfo;
import com.relyon.economizai.model.User;
import com.relyon.economizai.model.enums.AcquisitionChannel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AttributionResolverTest {

    private final AttributionResolver resolver = new AttributionResolver();

    private AcquisitionChannel channelFor(AttributionInfo attribution) {
        var user = new User();
        resolver.applyTo(user, attribution);
        return user.getAcquisitionChannel();
    }

    @Test
    void nullAttributionIsUnknown() {
        assertThat(channelFor(null)).isEqualTo(AcquisitionChannel.UNKNOWN);
    }

    @Test
    void emptyAttributionIsUnknown() {
        var empty = new AttributionInfo(null, "", "  ", null, null, "", null, null);
        assertThat(channelFor(empty)).isEqualTo(AcquisitionChannel.UNKNOWN);
    }

    @Test
    void instagramPaidMediumIsInstagramPaid() {
        var attribution = new AttributionInfo("instagram", "paid", "setembro", null, null, null, null, "/");
        assertThat(channelFor(attribution)).isEqualTo(AcquisitionChannel.INSTAGRAM_PAID);
    }

    @Test
    void fbclidWithoutPaidMediumStillCountsAsPaidMeta() {
        var attribution = new AttributionInfo("instagram", null, null, null, null, "fb.abc123", null, "/promo");
        assertThat(channelFor(attribution)).isEqualTo(AcquisitionChannel.INSTAGRAM_PAID);
    }

    @Test
    void googleCpcIsGooglePaid() {
        var attribution = new AttributionInfo("google", "cpc", "brand", null, null, null, null, null);
        assertThat(channelFor(attribution)).isEqualTo(AcquisitionChannel.GOOGLE_PAID);
    }

    @Test
    void unknownPaidSourceIsPaidOther() {
        var attribution = new AttributionInfo("tiktok", "paid_social", "viral", null, null, null, null, null);
        assertThat(channelFor(attribution)).isEqualTo(AcquisitionChannel.PAID_OTHER);
    }

    @Test
    void taggedButNonPaidIsOrganic() {
        var attribution = new AttributionInfo("newsletter", "email", "welcome", null, null, null, null, null);
        assertThat(channelFor(attribution)).isEqualTo(AcquisitionChannel.ORGANIC);
    }

    @Test
    void onlyReferrerIsReferral() {
        var attribution = new AttributionInfo(null, null, null, null, null, null, "https://blog.example.com", null);
        assertThat(channelFor(attribution)).isEqualTo(AcquisitionChannel.REFERRAL);
    }

    @Test
    void onlyLandingPathIsDirect() {
        var attribution = new AttributionInfo(null, null, null, null, null, null, null, "/");
        assertThat(channelFor(attribution)).isEqualTo(AcquisitionChannel.DIRECT);
    }

    @Test
    void rawParamsArePersistedAndOverlongValuesTruncated() {
        var longCampaign = "x".repeat(300);
        var attribution = new AttributionInfo("instagram", "paid", longCampaign, "content", "term", "fbclid", "ref", "/");
        var user = new User();
        resolver.applyTo(user, attribution);
        assertThat(user.getUtmSource()).isEqualTo("instagram");
        assertThat(user.getUtmCampaign()).hasSize(200);
        assertThat(user.getAttributionClickId()).isEqualTo("fbclid");
    }
}
