-- Two more groups that are ignored from the start (spec 022, "Groups ignored by default"), as in V17.

INSERT OR IGNORE INTO ignore_rule (kind, value, label, created_at)
SELECT 'GROUP', noisy.name, noisy.name, CAST(strftime('%s', 'now') AS INTEGER) * 1000
FROM (SELECT 'de.comp.os.ms-windows' AS name
      UNION ALL SELECT 'de.alt.sources.amiga') AS noisy
WHERE NOT EXISTS (SELECT 1
                  FROM subscription
                  JOIN newsgroup ON newsgroup.id = subscription.newsgroup_id
                  WHERE newsgroup.name = noisy.name);
