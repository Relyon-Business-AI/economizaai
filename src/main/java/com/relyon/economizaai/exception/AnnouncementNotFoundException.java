package com.relyon.economizaai.exception;

public class AnnouncementNotFoundException extends DomainException {

    public AnnouncementNotFoundException() {
        super("announcement.not.found");
    }
}
