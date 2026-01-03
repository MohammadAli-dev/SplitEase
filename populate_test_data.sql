-- ==========================================
-- TEST DATA SCRIPT
-- Usage: Run this in Android Studio Database Inspector
--        or via sqlite3 tool on the database file.
-- ==========================================

-- 1. Insert Mock Users
-- Note: 'user_1' is assumed to be YOU. If your app uses a specific ID for the logged-in user,
-- please update 'user_1' to that ID, or just treat 'user_1' as another member.
INSERT OR IGNORE INTO users (id, name, email, profileUrl) VALUES 
('user_1', 'Ali', 'Ali@test.com', NULL),
('user_2', 'Alice', 'Alice@test.com', NULL),
('user_3', 'Bob', 'Bob@test.com', NULL),
('user_4', 'Charlie', 'Charlie@test.com', NULL);

-- 2. Insert a Test Group
-- 2. Insert a Test Group with trip dates
INSERT OR IGNORE INTO expense_groups (id, name, type, coverUrl, createdBy, hasTripDates, tripStartDate, tripEndDate) VALUES 
('group_hawaii', 'Hawaii Trip 🌴', 'TRIP', NULL, 'user_1', 1, 1704067200000, 1704153600000); 
('group_hawaii', 'Hawaii Trip 🌴', 'TRIP', NULL, 'user_1');

-- 3. Add Members to Group
-- Timestamp roughly corresponds to Jan 1, 2024
INSERT OR IGNORE INTO group_members (groupId, userId, joinedAt) VALUES 
('group_hawaii', 'user_1', 1704067200000),
('group_hawaii', 'user_2', 1704067200000),
('group_hawaii', 'user_3', 1704067200000),
('group_hawaii', 'user_4', 1704067200000);

-- OPTIONAL: Insert a mock expense (Paid by Alice, split equally)
-- Only run this if you want pre-filled expenses.
/*
INSERT OR IGNORE INTO expenses (id, groupId, title, amount, currency, paidBy, date, createdBy) VALUES
('exp_1', 'group_hawaii', 'Dinner', 120.00, 'USD', 'user_2', 1704153600000, 'user_2');

INSERT OR IGNORE INTO expense_splits (expenseId, userId, amount) VALUES
('exp_1', 'user_1', 30.00),
('exp_1', 'user_2', 30.00),
('exp_1', 'user_3', 30.00),
('exp_1', 'user_4', 30.00);
*/
