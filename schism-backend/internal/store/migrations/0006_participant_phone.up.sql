-- Participants can carry a phone number (normalized digits). When a user later registers with the
-- same phone, their account is linked to those participants and the groups appear for them.
ALTER TABLE participants ADD COLUMN phone TEXT;
CREATE INDEX participants_phone_idx ON participants (phone) WHERE phone IS NOT NULL;
CREATE INDEX participants_user_id_idx ON participants (user_id) WHERE user_id IS NOT NULL;
