package com.relyon.economizaai.exception;

public class InvalidCampaignScheduleException extends DomainException {

    public InvalidCampaignScheduleException() {
        super("campaign.schedule.past");
    }
}
