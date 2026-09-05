-- =====================================================================
-- R__seed_subscription_provider_catalog
-- Repeatable migration (Flyway "R__" prefix, no version number).
--
-- Unlike V-prefixed migrations, this re-runs automatically whenever
-- its checksum changes (e.g. providers are added below) and always
-- applies after all versioned migrations. That fits this data's
-- actual behavior better than a one-time V migration would: the
-- provider catalog is expected to grow over time, the app functions
-- fine with an empty catalog (customers can always add a custom
-- subscription), and ON CONFLICT DO NOTHING was already needed to
-- make re-runs safe — which is the signal this belongs here rather
-- than in a versioned migration.
--
-- Populates subscription_providers with common third-party services.
-- =====================================================================

INSERT INTO subscription_providers (name, category, website_url) VALUES
    -- Streaming video
    ('Netflix',            'streaming_video', 'https://www.netflix.com'),
    ('Apple TV+',          'streaming_video', 'https://tv.apple.com'),
    ('Disney+',            'streaming_video', 'https://www.disneyplus.com'),
    ('Amazon Prime Video', 'streaming_video', 'https://www.primevideo.com'),
    ('Hulu',                'streaming_video', 'https://www.hulu.com'),
    ('Max',                'streaming_video', 'https://www.max.com'),
    ('Paramount+',          'streaming_video', 'https://www.paramountplus.com'),
    ('Peacock',            'streaming_video', 'https://www.peacocktv.com'),
    ('YouTube Premium',    'streaming_video', 'https://www.youtube.com/premium'),
    ('ESPN+',              'streaming_video', 'https://www.espn.com/espnplus'),

    -- Music & audio
    ('Spotify',            'music', 'https://www.spotify.com'),
    ('Apple Music',        'music', 'https://music.apple.com'),
    ('YouTube Music',      'music', 'https://music.youtube.com'),
    ('Tidal',              'music', 'https://tidal.com'),
    ('Amazon Music',       'music', 'https://music.amazon.com'),
    ('Audible',            'music', 'https://www.audible.com'),

    -- Cloud storage & productivity
    ('iCloud+',            'cloud_storage', 'https://www.icloud.com'),
    ('Google One',          'cloud_storage', 'https://one.google.com'),
    ('Dropbox',            'cloud_storage', 'https://www.dropbox.com'),
    ('Microsoft 365',      'software',      'https://www.microsoft.com/microsoft-365'),
    ('Google Workspace',   'software',      'https://workspace.google.com'),
    ('Notion',              'software',      'https://www.notion.so'),
    ('Adobe Creative Cloud','software',     'https://www.adobe.com/creativecloud.html'),
    ('1Password',           'vpn_security',  'https://1password.com'),
    ('NordVPN',            'vpn_security',  'https://nordvpn.com'),
    ('LastPass',            'vpn_security',  'https://www.lastpass.com'),

    -- News & media
    ('The New York Times', 'news_media', 'https://www.nytimes.com'),
    ('The Wall Street Journal', 'news_media', 'https://www.wsj.com'),
    ('The Washington Post', 'news_media', 'https://www.washingtonpost.com'),
    ('Medium',              'news_media', 'https://medium.com'),

    -- Fitness & wellness
    ('Peloton',             'fitness', 'https://www.onepeloton.com'),
    ('Calm',                'fitness', 'https://www.calm.com'),
    ('Headspace',           'fitness', 'https://www.headspace.com'),
    ('Strava',              'fitness', 'https://www.strava.com'),
    ('Planet Fitness',      'fitness', 'https://www.planetfitness.com'),

    -- Gaming
    ('Xbox Game Pass',      'gaming', 'https://www.xbox.com/game-pass'),
    ('PlayStation Plus',    'gaming', 'https://www.playstation.com/ps-plus'),
    ('Nintendo Switch Online', 'gaming', 'https://www.nintendo.com/switch/online-service'),

    -- Food & delivery
    ('Amazon Prime',        'food_delivery', 'https://www.amazon.com/prime'),
    ('DoorDash DashPass',   'food_delivery', 'https://www.doordash.com/dashpass'),
    ('Instacart+',          'food_delivery', 'https://www.instacart.com/instacart-plus'),

    -- Education
    ('LinkedIn Learning',   'education', 'https://www.linkedin.com/learning'),
    ('MasterClass',         'education', 'https://www.masterclass.com'),
    ('Duolingo Plus',       'education', 'https://www.duolingo.com')

ON CONFLICT (name) DO NOTHING;
