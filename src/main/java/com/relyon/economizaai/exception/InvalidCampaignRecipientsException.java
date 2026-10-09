package com.relyon.economizaai.exception;

public class InvalidCampaignRecipientsException extends DomainException {

    public InvalidCampaignRecipientsException() {
        super("campaign.recipients.required");
    }
}
