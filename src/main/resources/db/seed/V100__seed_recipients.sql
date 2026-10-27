-- Demo data: saved bank accounts for the mock wallet user 1000. Recipients CRUD is designed, not coded.
INSERT INTO recipient (id, user_id, first_name, last_name, national_id, routing_number, account_number, bank_name,
                       currency, created_at)
VALUES ('6f1c2b8e-3a4d-4e5f-9a6b-7c8d9e0f1a2b', 1000, 'Lucho', 'Rodriguez', '3327809', '211927207', '1885226711',
        'Bank of America', 'USD', CURRENT_TIMESTAMP),
       ('0a9b8c7d-6e5f-4a3b-8c2d-1e0f9a8b7c6d', 1000, 'Lucho', 'Rodriguez', '3327809', '623852453', '2300299907',
        'Chase', 'USD', CURRENT_TIMESTAMP);
