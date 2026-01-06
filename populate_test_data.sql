-- ==========================================
-- TEST DATA SCRIPT
-- Usage: Run this in Android Studio Database Inspector
--        or via sqlite3 tool on the database file.
-- Schema Version: 6 (with ownership columns)
-- ==========================================

-- 1. Insert Mock Users
-- Note: 'user_1' is assumed to be YOU. If your app uses a specific ID for the logged-in user,
-- please update 'user_1' to that ID, or just treat 'user_1' as another member.
INSERT OR IGNORE INTO users (id, name, email, profileUrl) VALUES
('45c7eae3-e464-4582-a9c1-869cc1acfad9', 'Ali', 'ali@test.com', NULL),
('user_2', 'Alice', 'Alice@test.com', NULL),
('user_3', 'Bob', 'Bob@test.com', NULL),
('user_4', 'Charlie', 'Charlie@test.com', NULL);

-- 2. Insert a Test Group
-- with trip dates and ownership fields (createdByUserId, lastModifiedByUserId)
INSERT OR IGNORE INTO expense_groups (id, name, type, coverUrl, createdBy, hasTripDates, tripStartDate, tripEndDate, createdByUserId, lastModifiedByUserId) VALUES
('group_hawaii', 'Hawaii Trip 🌴', 'TRIP', NULL, '45c7eae3-e464-4582-a9c1-869cc1acfad9', 1, 1704067200000, 1704153600000, '45c7eae3-e464-4582-a9c1-869cc1acfad9', '45c7eae3-e464-4582-a9c1-869cc1acfad9');-- Timestamp roughly corresponds to Jan 1, 2024
INSERT OR IGNORE INTO group_members (groupId, userId, joinedAt) VALUES
('group_hawaii', '45c7eae3-e464-4582-a9c1-869cc1acfad9', 1704067200000),
('group_hawaii', 'user_2', 1704067200000),
('group_hawaii', 'user_3', 1704067200000),
('group_hawaii', 'user_4', 1704067200000);

--Insert a mock expense (Paid by Alice, split equally) (OPTIONAL:)
-- Only run this if you want pre-filled expenses.

INSERT OR IGNORE INTO expenses (id, groupId, title, amount, currency, payerId, date, createdBy, createdByUserId, lastModifiedByUserId) VALUES
('exp_1', 'group_hawaii', 'Dinner', 1200.00, 'INR', 'user_2', 1704153600000, 'user_2', 'user_2', 'user_2');

INSERT OR IGNORE INTO expense_splits (expenseId, userId, amount) VALUES
('exp_1', '45c7eae3-e464-4582-a9c1-869cc1acfad9', 300.00),
('exp_1', 'user_2', 300.00),
('exp_1', 'user_3', 300.00),
('exp_1', 'user_4', 300.00);


