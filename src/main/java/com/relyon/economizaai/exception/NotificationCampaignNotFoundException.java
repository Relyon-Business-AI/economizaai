package com.relyon.economizaai.exception;

public class NotificationCampaignNotFoundException extends DomainException {

    public NotificationCampaignNotFoundException(String campaignId) {
        super("campaign.not.found", campaignId);
    }
}
