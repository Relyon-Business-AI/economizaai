-- In-app signups carry no web attribution (the stores pass nothing on) and were stamped UNKNOWN.
-- The registration platform still tells which store they came through.
UPDATE users SET acquisition_channel = 'APP_STORE'
 WHERE (acquisition_channel IS NULL OR acquisition_channel = 'UNKNOWN') AND registration_platform = 'IOS';
UPDATE users SET acquisition_channel = 'GOOGLE_PLAY'
 WHERE (acquisition_channel IS NULL OR acquisition_channel = 'UNKNOWN') AND registration_platform = 'ANDROID';
