-- The separate server for posting (spec 002, "Posting server"). Some providers take posts only on another host than
-- the one that is read from. All columns are empty when posts go to the server itself.
ALTER TABLE nntp_server ADD COLUMN posting_host TEXT;
ALTER TABLE nntp_server ADD COLUMN posting_port INTEGER;
ALTER TABLE nntp_server ADD COLUMN posting_tls_mode TEXT;
-- Empty when the posting server uses the login of the server; otherwise its own authentication.
ALTER TABLE nntp_server ADD COLUMN posting_auth_method TEXT;
ALTER TABLE nntp_server ADD COLUMN posting_username TEXT;
-- The same format as password_encrypted. Never the password itself.
ALTER TABLE nntp_server ADD COLUMN posting_password_encrypted BLOB;
