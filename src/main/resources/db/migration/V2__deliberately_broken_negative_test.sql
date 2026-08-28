-- deliberately invalid, step 7.3 negative test - proves the presync hook aborts a bad migration. reverted immediately after.
ALTER TABLE this_table_does_not_exist ADD COLUMN nope TEXT;
