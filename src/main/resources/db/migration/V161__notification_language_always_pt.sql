-- The app has no language selector: users.locale was captured from the device language at
-- signup, so an "en" there was never a choice. Off-request messages (push, e-mail, WhatsApp)
-- are now always written in Portuguese; align the rows already stored. The column stays.
UPDATE users SET locale = 'pt' WHERE locale <> 'pt';
