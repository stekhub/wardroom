-- Groups that are ignored from the start (spec 022, "Groups ignored by default"). Their names do not show that
-- they are no place for discussions, so no naming rule fits them; the owner removes a rule in the settings to see
-- the group again.

-- A subscribed group cannot be ignored, so an installation that subscribed to one of them keeps it visible.
INSERT OR IGNORE INTO ignore_rule (kind, value, label, created_at)
SELECT 'GROUP', noisy.name, noisy.name, CAST(strftime('%s', 'now') AS INTEGER) * 1000
FROM (SELECT 'misc.phone.mobile.android' AS name
      UNION ALL SELECT 'alt.dvdnordic.org'
      UNION ALL SELECT 'free.usenet'
      UNION ALL SELECT 'free.pt'
      UNION ALL SELECT 'a2i.b'
      UNION ALL SELECT 'zzz.3') AS noisy
WHERE NOT EXISTS (SELECT 1
                  FROM subscription
                  JOIN newsgroup ON newsgroup.id = subscription.newsgroup_id
                  WHERE newsgroup.name = noisy.name);
