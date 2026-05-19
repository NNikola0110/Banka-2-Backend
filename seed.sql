-- ============================================================
-- Banka 2025 — Seed Data
-- ============================================================
-- Lozinke za testiranje:
--   Admin korisnici (users tabela):    Admin12345
--   Obicni klijenti (users tabela):    Klijent12345
--   Zaposleni (employees tabela):      Zaposleni12
-- ============================================================

-- (MySQL sql_mode reset uklonjen — PostgreSQL)

-- Sacekaj da Hibernate kreira tabele (ddl-auto=update)
-- Ovaj fajl se izvrsava posle kreiranja baze

-- ============================================================
-- USERS (klijenti i admini koji se loguju kroz /auth/login)
-- ============================================================

INSERT INTO users (first_name, last_name, email, password, username, phone, address, active, role)
VALUES
  -- ADMIN korisnici
  ('Marko', 'Petrović', 'marko.petrovic@banka.rs',
   '$2b$10$2o//nneiTVurujS8ou5Snu3qNbF3Q20CbPnLc9ag2q0YIO1R3SyZG',
   'marko.petrovic', '+381 63 111 2233', 'Knez Mihailova 15, Beograd', 1, 'ADMIN'),

  ('Jelena', 'Đorđević', 'jelena.djordjevic@banka.rs',
   '$2b$10$2o//nneiTVurujS8ou5Snu3qNbF3Q20CbPnLc9ag2q0YIO1R3SyZG',
   'jelena.djordjevic', '+381 64 222 3344', 'Bulevar Oslobođenja 42, Novi Sad', 1, 'ADMIN'),

  -- Obicni klijenti
  ('Stefan', 'Jovanović', 'stefan.jovanovic@gmail.com',
   '$2b$10$FUjcSzK7CZKeX53YVU4JjeOIXLt5axbipO85OlQqw5Dopg47zfgRG',
   'stefan.jovanovic', '+381 65 333 4455', 'Cara Dušana 8, Niš', 1, 'CLIENT'),

  ('Milica', 'Nikolić', 'milica.nikolic@gmail.com',
   '$2b$10$FUjcSzK7CZKeX53YVU4JjeOIXLt5axbipO85OlQqw5Dopg47zfgRG',
   'milica.nikolic', '+381 66 444 5566', 'Vojvode Stepe 23, Beograd', 1, 'CLIENT'),

  ('Lazar', 'Ilić', 'lazar.ilic@yahoo.com',
   '$2b$10$FUjcSzK7CZKeX53YVU4JjeOIXLt5axbipO85OlQqw5Dopg47zfgRG',
   'lazar.ilic', '+381 60 555 6677', 'Bulevar Kralja Petra 71, Kragujevac', 1, 'CLIENT'),

  ('Ana', 'Stojanović', 'ana.stojanovic@hotmail.com',
   '$2b$10$FUjcSzK7CZKeX53YVU4JjeOIXLt5axbipO85OlQqw5Dopg47zfgRG',
   'ana.stojanovic', '+381 69 666 7788', 'Đorđa Stanojevića 12, Beograd', 1, 'CLIENT'),

  -- Neaktivan klijent (za testiranje)
  ('Nemanja', 'Savić', 'nemanja.savic@gmail.com',
   '$2b$10$FUjcSzK7CZKeX53YVU4JjeOIXLt5axbipO85OlQqw5Dopg47zfgRG',
   'nemanja.savic', '+381 62 777 8899', 'Terazije 5, Beograd', 0, 'CLIENT');


-- ============================================================
-- EMPLOYEES (zaposleni u banci)
-- ============================================================

INSERT INTO employees (first_name, last_name, date_of_birth, gender, email, phone, address, username, password, salt_password, position, department, active)
VALUES
  -- Admini (takodje zaposleni - "svaki admin je i supervizor")
  ('Marko', 'Petrović', '1985-05-20', 'M', 'marko.petrovic@banka.rs',
   '+381 63 111 2233', 'Knez Mihailova 15, Beograd', 'marko.petrovic',
   '$2b$10$YdFawauXoIBNqlhpBkFpKuDeMqGXlFR6g6T.sj3yXVFOdFDSmZzPG',
   'c2VlZF9zYWx0X2FkbTFfXw==',
   'Direktor', 'Uprava', 1),

  ('Jelena', 'Đorđević', '1987-11-12', 'F', 'jelena.djordjevic@banka.rs',
   '+381 63 222 3344', 'Bulevar Kralja Aleksandra 73, Beograd', 'jelena.djordjevic',
   '$2b$10$YdFawauXoIBNqlhpBkFpKuDeMqGXlFR6g6T.sj3yXVFOdFDSmZzPG',
   'c2VlZF9zYWx0X2FkbTJfXw==',
   'Zamenik direktora', 'Uprava', 1),

  ('Nikola', 'Milenković', '1988-03-15', 'M', 'nikola.milenkovic@banka.rs',
   '+381 63 100 2000', 'Nemanjina 4, Beograd', 'nikola.milenkovic',
   '$2b$10$lqAByD7N8elcbkNzut14L.dsZTHrWGL5r3qrp9KvzPw58.AzE4eHG',
   'c2VlZF9zYWx0XzAwMDFfXw==',
   'Team Lead', 'IT', 1),

  ('Tamara', 'Pavlović', '1992-07-22', 'F', 'tamara.pavlovic@banka.rs',
   '+381 64 200 3000', 'Kneza Miloša 32, Beograd', 'tamara.pavlovic',
   '$2b$10$727ZuuF8vHqGZyMrUTagCOZzhnvcV6Egf9198l5wEzyo07quVYkwq',
   'c2VlZF9zYWx0XzAwMDJfXw==',
   'Software Developer', 'IT', 1),

  ('Đorđe', 'Janković', '1985-11-03', 'M', 'djordje.jankovic@banka.rs',
   '+381 65 300 4000', 'Bulevar Mihajla Pupina 10, Novi Sad', 'djordje.jankovic',
   '$2b$10$xV3rnn442L9OG/tW6cz1TeR.hHwCamR/bO9Am3PFcrkMqG9PMiiYe',
   'c2VlZF9zYWx0XzAwMDNfXw==',
   'HR Manager', 'HR', 1),

  ('Maja', 'Ristić', '1995-01-18', 'F', 'maja.ristic@banka.rs',
   '+381 66 400 5000', 'Trg Republike 3, Beograd', 'maja.ristic',
   '$2b$10$00rB0B.rYUHcyAJc61hh2e3TjxI7zpQ.BUIR2ZchOjyaVE0AXkQYG',
   'c2VlZF9zYWx0XzAwMDRfXw==',
   'Accountant', 'Finance', 1),

  ('Vuk', 'Obradović', '1990-09-07', 'M', 'vuk.obradovic@banka.rs',
   '+381 60 500 6000', 'Železnička 15, Niš', 'vuk.obradovic',
   '$2b$10$g8WmJQ5QRHkJy59X5wxYf.Cfn5K9904fSiLY5QHUvCKfgOBLsDlAS',
   'c2VlZF9zYWx0XzAwMDVfXw==',
   'Supervisor', 'Operations', 0);

-- ============================================================
-- CURRENCIES (valute koje banka podrzava)
-- ============================================================
INSERT INTO currencies (id, code, name, symbol, country, description, active) VALUES
(1, 'EUR', 'Euro', '€', 'European Union', 'Euro – official currency of the Eurozone', 1),
(2, 'CHF', 'Swiss Franc', 'CHF', 'Switzerland', 'Swiss Franc – currency of Switzerland', 1),
(3, 'USD', 'US Dollar', '$', 'United States', 'US Dollar – currency of the United States', 1),
(4, 'GBP', 'British Pound', '£', 'United Kingdom', 'British Pound – currency of the UK', 1),
(5, 'JPY', 'Japanese Yen', '¥', 'Japan', 'Japanese Yen – currency of Japan', 1),
(6, 'CAD', 'Canadian Dollar', '$', 'Canada', 'Canadian Dollar – currency of Canada', 1),
(7, 'AUD', 'Australian Dollar', '$', 'Australia', 'Australian Dollar – currency of Australia', 1),
(8, 'RSD', 'Serbian Dinar', 'RSD', 'Serbia', 'Serbian Dinar – currency of Serbia', 1);

-- ============================================================
-- EMPLOYEE PERMISSIONS
-- ============================================================

-- Marko Petrović — Direktor / Admin (sve permisije)
INSERT INTO employee_permissions (employee_id, permission)
SELECT e.id, p.permission
FROM employees e
CROSS JOIN (
  SELECT 'ADMIN' AS permission UNION ALL
  SELECT 'TRADE_STOCKS' UNION ALL
  SELECT 'VIEW_STOCKS' UNION ALL
  SELECT 'CREATE_CONTRACTS' UNION ALL
  SELECT 'CREATE_INSURANCE' UNION ALL
  SELECT 'SUPERVISOR' UNION ALL
  SELECT 'AGENT'
) p
WHERE e.email = 'marko.petrovic@banka.rs'
AND NOT EXISTS (
  SELECT 1 FROM employee_permissions ep WHERE ep.employee_id = e.id AND ep.permission = p.permission
);

-- Jelena Đorđević — Zamenik / Admin (sve permisije)
INSERT INTO employee_permissions (employee_id, permission)
SELECT e.id, p.permission
FROM employees e
CROSS JOIN (
  SELECT 'ADMIN' AS permission UNION ALL
  SELECT 'TRADE_STOCKS' UNION ALL
  SELECT 'VIEW_STOCKS' UNION ALL
  SELECT 'CREATE_CONTRACTS' UNION ALL
  SELECT 'CREATE_INSURANCE' UNION ALL
  SELECT 'SUPERVISOR' UNION ALL
  SELECT 'AGENT'
) p
WHERE e.email = 'jelena.djordjevic@banka.rs'
AND NOT EXISTS (
  SELECT 1 FROM employee_permissions ep WHERE ep.employee_id = e.id AND ep.permission = p.permission
);

-- Nikola Milenković — Team Lead, Supervizor (bez ADMIN permisije)
INSERT INTO employee_permissions (employee_id, permission)
SELECT e.id, p.permission
FROM employees e
CROSS JOIN (
  SELECT 'TRADE_STOCKS' AS permission UNION ALL
  SELECT 'VIEW_STOCKS' UNION ALL
  SELECT 'CREATE_CONTRACTS' UNION ALL
  SELECT 'CREATE_INSURANCE' UNION ALL
  SELECT 'SUPERVISOR'
) p
WHERE e.email = 'nikola.milenkovic@banka.rs'
AND NOT EXISTS (
  SELECT 1 FROM employee_permissions ep WHERE ep.employee_id = e.id AND ep.permission = p.permission
);

-- Tamara Pavlović — Agent (stocks + contracts + agent)
INSERT INTO employee_permissions (employee_id, permission)
SELECT e.id, p.permission
FROM employees e
CROSS JOIN (
  SELECT 'VIEW_STOCKS' AS permission UNION ALL
  SELECT 'TRADE_STOCKS' UNION ALL
  SELECT 'CREATE_CONTRACTS' UNION ALL
  SELECT 'AGENT'
) p
WHERE e.email = 'tamara.pavlovic@banka.rs'
AND NOT EXISTS (
  SELECT 1 FROM employee_permissions ep WHERE ep.employee_id = e.id AND ep.permission = p.permission
);

-- Đorđe Janković — Agent (stocks + agent)
INSERT INTO employee_permissions (employee_id, permission)
SELECT e.id, p.permission
FROM employees e
CROSS JOIN (
  SELECT 'VIEW_STOCKS' AS permission UNION ALL
  SELECT 'TRADE_STOCKS' UNION ALL
  SELECT 'AGENT'
) p
WHERE e.email = 'djordje.jankovic@banka.rs'
AND NOT EXISTS (
  SELECT 1 FROM employee_permissions ep WHERE ep.employee_id = e.id AND ep.permission = p.permission
);

-- Maja Ristić — Agent (insurance + contracts + stocks + agent)
INSERT INTO employee_permissions (employee_id, permission)
SELECT e.id, p.permission
FROM employees e
CROSS JOIN (
  SELECT 'CREATE_INSURANCE' AS permission UNION ALL
  SELECT 'CREATE_CONTRACTS' UNION ALL
  SELECT 'VIEW_STOCKS' UNION ALL
  SELECT 'TRADE_STOCKS' UNION ALL
  SELECT 'AGENT'
) p
WHERE e.email = 'maja.ristic@banka.rs'
AND NOT EXISTS (
  SELECT 1 FROM employee_permissions ep WHERE ep.employee_id = e.id AND ep.permission = p.permission
);

-- Vuk Obradović — Supervisor (neaktivan, ima supervisor + view stocks)
INSERT INTO employee_permissions (employee_id, permission)
SELECT e.id, p.permission
FROM employees e
CROSS JOIN (
  SELECT 'SUPERVISOR' AS permission UNION ALL
  SELECT 'VIEW_STOCKS'
) p
WHERE e.email = 'vuk.obradovic@banka.rs'
AND NOT EXISTS (
  SELECT 1 FROM employee_permissions ep WHERE ep.employee_id = e.id AND ep.permission = p.permission
);


-- ============================================================
-- CLIENTS (klijenti banke — vlasnici racuna)
-- ============================================================
-- Email adrese se MORAJU poklapati sa users tabelom (role='CLIENT')
-- jer AccountServiceImpl trazi klijenta po email-u iz JWT tokena.
-- Lozinke: Klijent12345

INSERT INTO clients (first_name, last_name, date_of_birth, gender, email, phone, address,
                     password, salt_password, active, created_at)
VALUES
    ('Stefan', 'Jovanović', '1995-04-12', 'M', 'stefan.jovanovic@gmail.com',
     '+381 65 333 4455', 'Cara Dušana 8, Niš',
     '$2b$10$FUjcSzK7CZKeX53YVU4JjeOIXLt5axbipO85OlQqw5Dopg47zfgRG',
     'c2VlZF9jbGllbnRfMDFf', 1, NOW()),

    ('Milica', 'Nikolić', '1993-08-25', 'F', 'milica.nikolic@gmail.com',
     '+381 66 444 5566', 'Vojvode Stepe 23, Beograd',
     '$2b$10$FUjcSzK7CZKeX53YVU4JjeOIXLt5axbipO85OlQqw5Dopg47zfgRG',
     'c2VlZF9jbGllbnRfMDJf', 1, NOW()),

    ('Lazar', 'Ilić', '1990-12-01', 'M', 'lazar.ilic@yahoo.com',
     '+381 60 555 6677', 'Bulevar Kralja Petra 71, Kragujevac',
     '$2b$10$FUjcSzK7CZKeX53YVU4JjeOIXLt5axbipO85OlQqw5Dopg47zfgRG',
     'c2VlZF9jbGllbnRfMDNf', 1, NOW()),

    ('Ana', 'Stojanović', '1997-06-15', 'F', 'ana.stojanovic@hotmail.com',
     '+381 69 666 7788', 'Đorđa Stanojevića 12, Beograd',
     '$2b$10$FUjcSzK7CZKeX53YVU4JjeOIXLt5axbipO85OlQqw5Dopg47zfgRG',
     'c2VlZF9jbGllbnRfMDRf', 1, NOW());


-- ============================================================
-- COMPANIES (firme za poslovne racune)
-- ============================================================

INSERT INTO companies (id, name, registration_number, tax_number, activity_code, address,
                       majority_owner_id, active, is_state, is_bank, created_at)
VALUES
    (1, 'TechStar DOO', '12345678', '123456789', '62.01',
     'Bulevar Mihajla Pupina 10, Novi Beograd',
     NULL, 1, 0, 0, NOW()),
    (2, 'Green Food AD', '87654321', '987654321', '10.10',
     'Industrijska zona bb, Subotica',
     NULL, 1, 0, 0, NOW());

-- ============================================================
-- AUTHORIZED PERSONS (ovlascena lica za firme)
-- ============================================================
-- Milica (client_id=2) je ovlasceno lice za TechStar DOO (company_id=1)
INSERT INTO authorized_persons (client_id, company_id, created_at)
SELECT c.id, 1, NOW()
FROM clients c WHERE c.email = 'milica.nikolic@gmail.com'
AND NOT EXISTS (
    SELECT 1 FROM authorized_persons ap WHERE ap.client_id = c.id AND ap.company_id = 1
);


-- ============================================================
-- ACCOUNTS (racuni klijenata)
-- ============================================================
-- Enum vrednosti:
--   AccountType:    CHECKING, FOREIGN, BUSINESS, MARGIN
--   AccountSubtype: PERSONAL, SAVINGS, PENSION, YOUTH, STUDENT, UNEMPLOYED, SALARY, STANDARD
--   AccountStatus:  ACTIVE, INACTIVE
--
-- client_id:   1=Stefan, 2=Milica, 3=Lazar, 4=Ana
-- employee_id: 1=Nikola, 2=Tamara, 3=Djordje, 4=Maja
-- currency_id: 1=EUR, 2=CHF, 3=USD, 4=GBP, 5=JPY, 6=CAD, 7=AUD, 8=RSD

INSERT INTO accounts (account_number, account_type, account_subtype, currency_id,
                      client_id, company_id, employee_id,
                      balance, available_balance,
                      daily_limit, monthly_limit,
                      daily_spending, monthly_spending,
                      maintenance_fee, expiration_date, status, name, created_at)
VALUES
    -- ─── Stefan Jovanović (client_id=1) — 3 aktivna racuna ─────────────────
    ('222000112345678911', 'CHECKING', 'STANDARD', 8, 1, NULL, 1,
     185000.0000, 178000.0000,
     250000.0000, 1000000.0000,
     7000.0000, 45000.0000,
     255.0000, '2030-01-01', 'ACTIVE', 'Glavni račun', NOW()),

    ('222000112345678912', 'CHECKING', 'SAVINGS', 8, 1, NULL, 1,
     520000.0000, 520000.0000,
     100000.0000, 500000.0000,
     0.0000, 0.0000,
     150.0000, '2030-06-01', 'ACTIVE', 'Štednja', NOW()),

    ('222000121345678921', 'FOREIGN', 'PERSONAL', 1, 1, NULL, 2,
     2500.0000, 2350.0000,
     5000.0000, 20000.0000,
     150.0000, 800.0000,
     0.0000, '2030-01-01', 'ACTIVE', 'Euro račun', NOW()),

    -- ─── Milica Nikolić (client_id=2) — 1 licni + 1 poslovni ──────────────
    ('222000112345678913', 'CHECKING', 'STANDARD', 8, 2, NULL, 1,
     95000.0000, 92000.0000,
     250000.0000, 1000000.0000,
     3000.0000, 28000.0000,
     255.0000, '2031-03-15', 'ACTIVE', 'Lični račun', NOW()),

    -- Poslovni racun MORA imati samo company_id, ne client_id (Account
    -- ima @AssertTrue isOwnerValid() XOR validaciju). Milica je
    -- AuthorizedPerson preko `authorized_persons` tabele, ne direktan
    -- vlasnik. Bag prijavljen 10.05.2026 vece-3 — pre fix-a, svaki update
    -- ovog racuna je pucao sa "Racun mora imati vlasnika: ili klijenta
    -- ili kompaniju, ali ne oba".
    ('222000112345678914', 'BUSINESS', 'STANDARD', 8, NULL, 1, 2,
     1250000.0000, 1230000.0000,
     1000000.0000, 5000000.0000,
     20000.0000, 350000.0000,
     500.0000, '2032-01-01', 'ACTIVE', 'TechStar poslovanje', NOW()),

    -- Milicin devizni EUR
    ('222000121345678923', 'FOREIGN', 'PERSONAL', 1, 2, NULL, 1,
     3200.0000, 3200.0000,
     10000.0000, 50000.0000,
     0.0000, 0.0000,
     0.0000, '2031-06-01', 'ACTIVE', 'Euro devizni', NOW()),

    -- ─── Lazar Ilić (client_id=3) — 1 tekuci + 1 devizni USD + 1 devizni EUR
    ('222000112345678915', 'CHECKING', 'STANDARD', 8, 3, NULL, 3,
     310000.0000, 305000.0000,
     250000.0000, 1000000.0000,
     5000.0000, 62000.0000,
     255.0000, '2030-09-01', 'ACTIVE', 'Tekući', NOW()),

    ('222000121345678922', 'FOREIGN', 'PERSONAL', 3, 3, NULL, 3,
     1800.0000, 1800.0000,
     3000.0000, 15000.0000,
     0.0000, 0.0000,
     0.0000, '2031-01-01', 'ACTIVE', 'Dollar savings', NOW()),

    ('222000121345678924', 'FOREIGN', 'PERSONAL', 1, 3, NULL, 3,
     1500.0000, 1500.0000,
     5000.0000, 20000.0000,
     0.0000, 0.0000,
     0.0000, '2031-03-01', 'ACTIVE', 'Euro savings', NOW()),

    -- ─── Ana Stojanović (client_id=4) — 1 aktivan + 1 neaktivan + 1 devizni
    ('222000112345678916', 'CHECKING', 'STANDARD', 8, 4, NULL, 4,
     50000.0000, 50000.0000,
     250000.0000, 1000000.0000,
     0.0000, 0.0000,
     255.0000, '2028-01-01', 'INACTIVE', 'Stari račun', NOW()),

    ('222000112345678917', 'CHECKING', 'YOUTH', 8, 4, NULL, 4,
     72000.0000, 70500.0000,
     150000.0000, 600000.0000,
     1500.0000, 18000.0000,
     0.0000, '2031-06-01', 'ACTIVE', 'Račun za mlade', NOW()),

    ('222000121345678925', 'FOREIGN', 'PERSONAL', 1, 4, NULL, 4,
     800.0000, 800.0000,
     3000.0000, 15000.0000,
     0.0000, 0.0000,
     0.0000, '2031-09-01', 'ACTIVE', 'Euro račun', NOW());


-- ============================================================
-- BANK ACCOUNTS (Banka kao entitet — racuni u svim valutama)
-- ============================================================
-- Banka ima racune u svim 8 valuta za primanje provizija i isplatu kredita.
-- employee_id=1 (Nikola) kreirao, nema client_id ni company_id (bank internal).
-- NAPOMENA: Validacija zahteva client XOR company, ali bankini racuni
-- koriste company_id=NULL i client_id=NULL. Moramo privremeno koristiti
-- company za ovo ili napraviti izuzetak. Koristimo company_id=2 (Green Food)
-- kao placeholder jer je to banka u vlasnistvu... Alternativa: kreiramo posebnu
-- firmu "Banka 2025" kao company.

-- Prvo kreiramo firmu za banku (is_bank=1, is_state=0 — Celina 2 §73-78
-- "Nasa Banka = Firma" ima poseban status u sistemu)
INSERT INTO companies (id, name, registration_number, tax_number, activity_code, address,
                       majority_owner_id, active, is_state, is_bank, created_at)
VALUES
    (3, 'Banka 2025 Tim 2', '22200022', '222000222', '64.19',
     'Bulevar Kralja Aleksandra 73, Beograd',
     NULL, 1, 0, 1, NOW());

-- Drzava (Republika Srbija, is_state=1) — poseban entitet za uplatu poreza,
-- Celina 3 §47 "naša država = Firma" sa RSD racunom za porez na dobit.
INSERT INTO companies (id, name, registration_number, tax_number, activity_code, address,
                       majority_owner_id, active, is_state, is_bank, created_at)
VALUES
    (4, 'Republika Srbija', '17858459', '100002288', '84.11',
     'Nemanjina 11, Beograd',
     NULL, 1, 1, 0, NOW());

-- Bankini racuni u svim valutama
INSERT INTO accounts (account_number, account_type, account_subtype, currency_id,
                      client_id, company_id, employee_id,
                      balance, available_balance,
                      daily_limit, monthly_limit,
                      daily_spending, monthly_spending,
                      maintenance_fee, expiration_date, status, name, created_at)
VALUES
    ('222000100000000110', 'BUSINESS', 'STANDARD', 8, NULL, 3, 1,
     50000000.0000, 50000000.0000, 999999999.0000, 999999999.0000,
     0.0000, 0.0000, 0.0000, '2050-01-01', 'ACTIVE', 'Banka RSD', NOW()),

    ('222000100000000120', 'BUSINESS', 'STANDARD', 1, NULL, 3, 1,
     5000000.0000, 5000000.0000, 999999999.0000, 999999999.0000,
     0.0000, 0.0000, 0.0000, '2050-01-01', 'ACTIVE', 'Banka EUR', NOW()),

    ('222000100000000130', 'BUSINESS', 'STANDARD', 2, NULL, 3, 1,
     5000000.0000, 5000000.0000, 999999999.0000, 999999999.0000,
     0.0000, 0.0000, 0.0000, '2050-01-01', 'ACTIVE', 'Banka CHF', NOW()),

    ('222000100000000140', 'BUSINESS', 'STANDARD', 3, NULL, 3, 1,
     5000000.0000, 5000000.0000, 999999999.0000, 999999999.0000,
     0.0000, 0.0000, 0.0000, '2050-01-01', 'ACTIVE', 'Banka USD', NOW()),

    ('222000100000000150', 'BUSINESS', 'STANDARD', 4, NULL, 3, 1,
     5000000.0000, 5000000.0000, 999999999.0000, 999999999.0000,
     0.0000, 0.0000, 0.0000, '2050-01-01', 'ACTIVE', 'Banka GBP', NOW()),

    ('222000100000000160', 'BUSINESS', 'STANDARD', 5, NULL, 3, 1,
     500000000.0000, 500000000.0000, 999999999.0000, 999999999.0000,
     0.0000, 0.0000, 0.0000, '2050-01-01', 'ACTIVE', 'Banka JPY', NOW()),

    ('222000100000000170', 'BUSINESS', 'STANDARD', 6, NULL, 3, 1,
     5000000.0000, 5000000.0000, 999999999.0000, 999999999.0000,
     0.0000, 0.0000, 0.0000, '2050-01-01', 'ACTIVE', 'Banka CAD', NOW()),

    ('222000100000000180', 'BUSINESS', 'STANDARD', 7, NULL, 3, 1,
     5000000.0000, 5000000.0000, 999999999.0000, 999999999.0000,
     0.0000, 0.0000, 0.0000, '2050-01-01', 'ACTIVE', 'Banka AUD', NOW());

-- Tekuci dinarski racun drzave (za uplatu poreza)
INSERT INTO accounts (account_number, account_type, account_subtype, currency_id,
                      client_id, company_id, employee_id,
                      balance, available_balance,
                      daily_limit, monthly_limit,
                      daily_spending, monthly_spending,
                      maintenance_fee, expiration_date, status, name, created_at)
VALUES
    ('222000100000000200', 'CHECKING', 'STANDARD', 8, NULL, 4, 1,
     0.0000, 0.0000, 999999999.0000, 999999999.0000,
     0.0000, 0.0000, 0.0000, '2050-01-01', 'ACTIVE', 'Republika Srbija - poreski racun', NOW());


-- ============================================================
-- CARDS (kartice klijenata)
-- ============================================================
-- card_status: ACTIVE, BLOCKED, DEACTIVATED
-- account_id: koristimo racune iz gornjeg inserta
-- client_id:  1=Stefan, 2=Milica, 3=Lazar, 4=Ana

INSERT INTO cards (card_number, card_name, cvv, account_id, client_id,
                   card_limit, status, created_at, expiration_date)
VALUES
    -- Stefan: 1 kartica za tekuci RSD (account_id=1)
    ('4222001234567890', 'Visa Debit', '123', 1, 1,
     250000.0000, 'ACTIVE', '2026-01-15', '2030-01-15'),

    -- Stefan: 1 kartica za Euro racun (account_id=3)
    ('4222009876543210', 'Visa Debit', '456', 3, 1,
     5000.0000, 'ACTIVE', '2026-02-01', '2030-02-01'),

    -- Milica: 1 kartica za tekuci (account_id=4)
    ('4222005555666677', 'Visa Debit', '789', 4, 2,
     200000.0000, 'ACTIVE', '2026-01-20', '2030-01-20'),

    -- Milica: 1 kartica za poslovni (account_id=5) — max 1 za business
    ('4222003333444455', 'Visa Business', '321', 5, 2,
     1000000.0000, 'ACTIVE', '2026-02-10', '2030-02-10'),

    -- Lazar: 1 kartica za tekuci (account_id=7)
    ('4222007777888899', 'Visa Debit', '654', 7, 3,
     250000.0000, 'ACTIVE', '2026-03-01', '2030-03-01'),

    -- Ana: 1 kartica za youth racun (account_id=11), blokirana za testiranje
    ('4222001111222233', 'Visa Debit', '987', 11, 4,
     150000.0000, 'BLOCKED', '2026-01-01', '2030-01-01');

-- Demo CREDIT kartica + INTERNET_PREPAID kartice (T4A nova: prosirenje tipova).
-- Stefan: 1 CREDIT Mastercard + 1 INTERNET_PREPAID DinaCard (drugi slot 2 svog tekuceg racuna).
-- Milica: 1 INTERNET_PREPAID na svom Euro racunu (account_id=6).
INSERT INTO cards (card_number, card_name, cvv, account_id, client_id,
                   card_limit, card_type, card_category, prepaid_balance, credit_limit, outstanding_balance,
                   status, created_at, expiration_date, card_slot)
VALUES
    -- Stefan CREDIT MasterCard (account_id=1, slot=2 — drugi po redu kartica)
    ('5212003333112299', 'MasterCard Credit', '111', 1, 1,
     200000.0000, 'MASTERCARD', 'CREDIT', 0.0000, 500000.0000, 0.0000,
     'ACTIVE', '2026-03-01', '2030-03-01', 2),

    -- Milica INTERNET_PREPAID DinaCard (account_id=6, slot=1) — Euro
    ('9891001122334455', 'DinaCard Prepaid', '222', 6, 2,
     50000.0000, 'DINACARD', 'INTERNET_PREPAID', 1000.0000, 0.0000, 0.0000,
     'ACTIVE', '2026-03-10', '2030-03-10', 1);


-- ============================================================
-- PAYMENT RECIPIENTS (sacuvani primaoci placanja)
-- ============================================================

INSERT INTO payment_recipients (client_id, name, account_number, created_at)
SELECT c.id, 'Milica Nikolić', '222000112345678913', NOW()
FROM clients c WHERE c.email = 'stefan.jovanovic@gmail.com'
AND NOT EXISTS (
    SELECT 1 FROM payment_recipients pr WHERE pr.client_id = c.id AND pr.account_number = '222000112345678913'
);

INSERT INTO payment_recipients (client_id, name, account_number, created_at)
SELECT c.id, 'Lazar Ilić', '222000112345678915', NOW()
FROM clients c WHERE c.email = 'stefan.jovanovic@gmail.com'
AND NOT EXISTS (
    SELECT 1 FROM payment_recipients pr WHERE pr.client_id = c.id AND pr.account_number = '222000112345678915'
);

INSERT INTO payment_recipients (client_id, name, account_number, created_at)
SELECT c.id, 'Stefan Jovanović', '222000112345678911', NOW()
FROM clients c WHERE c.email = 'milica.nikolic@gmail.com'
AND NOT EXISTS (
    SELECT 1 FROM payment_recipients pr WHERE pr.client_id = c.id AND pr.account_number = '222000112345678911'
);

INSERT INTO payment_recipients (client_id, name, account_number, created_at)
SELECT c.id, 'Ana Stojanović', '222000112345678917', NOW()
FROM clients c WHERE c.email = 'lazar.ilic@yahoo.com'
AND NOT EXISTS (
    SELECT 1 FROM payment_recipients pr WHERE pr.client_id = c.id AND pr.account_number = '222000112345678917'
);




-- ============================================================
-- PHASE 0: Fund reservation backfill + bank trading racuni
-- ============================================================

-- Backfill availableBalance za postojece racune (ako je 0 ili NULL, postavi = balance)
UPDATE accounts SET available_balance = balance WHERE available_balance IS NULL OR available_balance = 0;

-- Osiguraj da je reservedAmount 0 za sve postojece racune
UPDATE accounts SET reserved_amount = 0 WHERE reserved_amount IS NULL;

-- Postavi default accountCategory = 'CLIENT' za sve postojece racune.
-- Napomena: Hibernate kreira MySQL ENUM kolonu sa abecedno sortiranim vrednostima,
-- pa je prva vrednost 'BANK_TRADING' i postaje implicitni default kad seed inserti
-- ne navedu account_category. Zato ovde forsirano postavljamo CLIENT svuda,
-- pa onda oznacavamo bankine racune kao BANK_TRADING.
UPDATE accounts SET account_category = 'CLIENT';

-- Postojeci bankini racuni (company_id=3, Banka 2025 Tim 2) se markiraju kao BANK_TRADING.
-- Izuzetak: poreski racun drzave (company_id=4) ostaje CLIENT.
UPDATE accounts SET account_category = 'BANK_TRADING' WHERE company_id = 3;

-- Namerno ne dodajemo dodatne BANK_TRADING racune za RSD/USD/EUR — postojeci
-- (222000100000000110/140/120) vec pokrivaju te valute i oznaceni su kao
-- BANK_TRADING gornjim UPDATE-om. Duplikati su obarali findBankAccountByCurrency
-- (Optional<Account> koji pada na 2 reda) pa ih ovde ne seed-ujemo.
-- Punjenje saldo bankinih racuna da FX transferi imaju kome da duguju:
UPDATE accounts SET balance = 500000000.0000, available_balance = 500000000.0000,
    daily_limit = 999999999.0000, monthly_limit = 999999999.0000
WHERE account_number = '222000100000000110';  -- RSD
UPDATE accounts SET balance = 5000000.0000, available_balance = 5000000.0000,
    daily_limit = 999999999.0000, monthly_limit = 999999999.0000
WHERE account_number = '222000100000000140';  -- USD
UPDATE accounts SET balance = 5000000.0000, available_balance = 5000000.0000,
    daily_limit = 999999999.0000, monthly_limit = 999999999.0000
WHERE account_number = '222000100000000120';  -- EUR


-- ============================================================
-- BANKA KAO KLIJENT (vlasnik banke) — Celina 4 (Nova) Napomena 1
-- ============================================================
-- Spec ref: Celina 4 (Nova) §4406-4435 (Napomena 1+2 — banka investira
-- preko "vlasnik banke" klijenta).
--
-- Banka se tretira kao obican klijent: Profit Banke portal "Pozicije u
-- fondovima" prikazuje pozicije sa userRole='CLIENT' i userId = bankov
-- client_id. Sami fondovi + pozicije (investment_funds /
-- client_fund_positions) su trgovinski domen i zive u trading_db
-- (vidi trading-seed.sql) posle cutover-a (pod-faza 2f) — ovde ostaje
-- samo "Banka 2 d.o.o." klijent u banka-core (clients tabela).
--
-- Idempotentno: WHERE NOT EXISTS pa se moze ponavljati pri re-seedu.
-- ============================================================

-- Banka kao klijent (vlasnik banke) — Celina 4 (Nova) Napomena 1
INSERT INTO clients (first_name, last_name, date_of_birth, gender, email, phone, address,
                     password, salt_password, active, created_at)
SELECT 'Banka 2', 'd.o.o.', '2025-01-01', 'OTHER', 'banka2.doo@banka.rs',
       '+381 11 000 0000', 'Bulevar Kralja Aleksandra 73, Beograd',
       '$2b$10$FUjcSzK7CZKeX53YVU4JjeOIXLt5axbipO85OlQqw5Dopg47zfgRG',
       'c2VlZF9iYW5rYV9kb29f', 1, NOW()
WHERE NOT EXISTS (
    SELECT 1 FROM clients WHERE email = 'banka2.doo@banka.rs'
);


-- ============================================================
-- INTER-BANK HANDSHAKE TEST SEED (Celina 5 — Tim 1 / Tim 2 razmena)
-- ============================================================
-- Dodatni racuni i klijent za inter-bank protokol handshake test
-- sa Tim 1. Dodato 11.05.2026 posle dogovora sa Aleksom (Tim 1 BE).
--
-- Sta omogucava:
-- 1) Stefan + Milica + Ana dobijaju USD racune — da mogu primati
--    premium u USD od inter-bank kupca u OTC accept flow-u
--    (OtcNegotiationService.resolveLocalAccount trazi seller-ov
--    racun u valuti premium-a; bez USD racuna kupac iz Banka 1
--    ne bi mogao da plati premium za AAPL/MSFT koji su u USD-u).
-- 2) Novi klijent C-7 "Mile Interbank" sa svim 4 valutama
--    (RSD/EUR/USD/CHF) — dedicated test target za Tim 1 handshake
--    skripte. Iznosi po valuti dovoljni za sve 15 scenarija.
-- 3) Stefan dodaje 8 javnih AAPL akcija (pored postojecih 10 MSFT)
--    da Tim 1 ima vise scenarija u GET /public-stock odgovoru
--    (multi-seller per ticker).
-- ============================================================

-- 1) Stefan USD racun (account_id ce biti auto-generisan, ne
--    konflikira sa explicit-ID-evima 1-22 jer sequence se resetuje)
INSERT INTO accounts (account_number, account_type, account_subtype, currency_id,
                      client_id, company_id, employee_id,
                      balance, available_balance,
                      daily_limit, monthly_limit,
                      daily_spending, monthly_spending,
                      maintenance_fee, expiration_date, status, name, created_at)
VALUES
    -- Stefan USD: 2500 USD pocetno stanje, za primanje premium-a od Tim 1 kupca
    ('222000131234567811', 'FOREIGN', 'STANDARD', 3, 1, NULL, 1,
     2500.0000, 2500.0000, 5000.0000, 50000.0000,
     0.0000, 0.0000, 0.0000, '2030-01-01', 'ACTIVE', 'Stefan USD', NOW()),
    -- Milica USD: 1800 USD pocetno, isto pravilo
    ('222000131234567812', 'FOREIGN', 'STANDARD', 3, 2, NULL, 1,
     1800.0000, 1800.0000, 5000.0000, 50000.0000,
     0.0000, 0.0000, 0.0000, '2030-01-01', 'ACTIVE', 'Milica USD', NOW()),
    -- Ana USD: 950 USD pocetno
    ('222000131234567813', 'FOREIGN', 'STANDARD', 3, 4, NULL, 1,
     950.0000, 950.0000, 5000.0000, 50000.0000,
     0.0000, 0.0000, 0.0000, '2030-01-01', 'ACTIVE', 'Ana USD', NOW());

-- 2) Mile Interbank — dedicated handshake test klijent (C-7)
INSERT INTO clients (first_name, last_name, date_of_birth, gender, email, phone, address,
                     password, salt_password, active, created_at)
SELECT 'Mile', 'Interbank', '1992-03-20', 'M', 'mile.interbank@banka.rs',
       '+381 65 777 8899', 'Inter-Bank Test 1, Beograd',
       '$2b$10$FUjcSzK7CZKeX53YVU4JjeOIXLt5axbipO85OlQqw5Dopg47zfgRG',
       'c2VlZF9pbnRlcmJhbmtf', 1, NOW()
WHERE NOT EXISTS (
    SELECT 1 FROM clients WHERE email = 'mile.interbank@banka.rs'
);

-- 3) Mile Interbank racuni — sve 4 osnovne valute, visoki balance-ovi
--    Tim 1 koristi `222000177777777XXX` prefix za njega da bude
--    lako prepoznatljiv u test scriptama.
INSERT INTO accounts (account_number, account_type, account_subtype, currency_id,
                      client_id, company_id, employee_id,
                      balance, available_balance,
                      daily_limit, monthly_limit,
                      daily_spending, monthly_spending,
                      maintenance_fee, expiration_date, status, name, created_at)
SELECT acc.account_number, acc.account_type, acc.account_subtype, acc.currency_id,
       c.id, NULL, 1,
       acc.balance, acc.balance,
       acc.daily_limit, acc.monthly_limit,
       0.0000, 0.0000, 0.0000, '2030-01-01', 'ACTIVE', acc.name, NOW()
FROM clients c
CROSS JOIN (VALUES
    ('222000177777777811', 'CHECKING', 'STANDARD', 8, 5000000.0000, 1000000.0000, 10000000.0000, 'Mile Interbank RSD'),
    ('222000177777777821', 'FOREIGN',  'STANDARD', 1,   50000.0000,   10000.0000,   100000.0000, 'Mile Interbank EUR'),
    ('222000177777777831', 'FOREIGN',  'STANDARD', 3,   50000.0000,   10000.0000,   100000.0000, 'Mile Interbank USD'),
    ('222000177777777841', 'FOREIGN',  'STANDARD', 2,   50000.0000,   10000.0000,   100000.0000, 'Mile Interbank CHF')
) AS acc(account_number, account_type, account_subtype, currency_id, balance, daily_limit, monthly_limit, name)
WHERE c.email = 'mile.interbank@banka.rs'
  AND NOT EXISTS (
      SELECT 1 FROM accounts WHERE account_number = acc.account_number
  );


-- ============================================================
-- SEQUENCE RESET — posle eksplicitnih ID INSERT-ova, sequence
-- mora da se sinhronizuje sa MAX(id) iz svake tabele, inace
-- sledeci Hibernate auto-generated INSERT pokusava ID koji
-- vec postoji (companies/currencies imaju eksplicitne ID-eve).
-- Bag prijavljen 10.05.2026 vece-2 (C2 Sc 4).
-- ============================================================

SELECT setval('companies_id_seq', (SELECT COALESCE(MAX(id), 1) FROM companies));
SELECT setval('currencies_id_seq', (SELECT COALESCE(MAX(id), 1) FROM currencies));

-- ============================================================
-- STEDNJA (Celina 2 nadogradnja) — kamatne stope + demo deposits
-- ============================================================

-- 8 valuta x 5 rokova = 40 stopa (RSD/EUR/USD/CHF/GBP/CAD/AUD/JPY x 3/6/12/24/36 meseci)
-- RSD: 2.5-5.0%, EUR/USD: 1.5-4.0%, CHF: 1.0-2.0%, GBP: 1.75-3.25%,
-- CAD: 1.5-3.0%, AUD: 1.75-3.25%, JPY: 0.5-2.5%

INSERT INTO savings_interest_rates (currency_id, term_months, annual_rate, active, effective_from, created_at) VALUES
  ((SELECT id FROM currencies WHERE code='RSD'),  3, 2.50, 1, '2026-01-01', NOW()),
  ((SELECT id FROM currencies WHERE code='RSD'),  6, 3.00, 1, '2026-01-01', NOW()),
  ((SELECT id FROM currencies WHERE code='RSD'), 12, 4.00, 1, '2026-01-01', NOW()),
  ((SELECT id FROM currencies WHERE code='RSD'), 24, 4.50, 1, '2026-01-01', NOW()),
  ((SELECT id FROM currencies WHERE code='RSD'), 36, 5.00, 1, '2026-01-01', NOW()),

  ((SELECT id FROM currencies WHERE code='EUR'),  3, 1.50, 1, '2026-01-01', NOW()),
  ((SELECT id FROM currencies WHERE code='EUR'),  6, 2.00, 1, '2026-01-01', NOW()),
  ((SELECT id FROM currencies WHERE code='EUR'), 12, 2.50, 1, '2026-01-01', NOW()),
  ((SELECT id FROM currencies WHERE code='EUR'), 24, 3.00, 1, '2026-01-01', NOW()),
  ((SELECT id FROM currencies WHERE code='EUR'), 36, 3.50, 1, '2026-01-01', NOW()),

  ((SELECT id FROM currencies WHERE code='USD'),  3, 2.00, 1, '2026-01-01', NOW()),
  ((SELECT id FROM currencies WHERE code='USD'),  6, 2.50, 1, '2026-01-01', NOW()),
  ((SELECT id FROM currencies WHERE code='USD'), 12, 3.00, 1, '2026-01-01', NOW()),
  ((SELECT id FROM currencies WHERE code='USD'), 24, 3.50, 1, '2026-01-01', NOW()),
  ((SELECT id FROM currencies WHERE code='USD'), 36, 4.00, 1, '2026-01-01', NOW()),

  ((SELECT id FROM currencies WHERE code='CHF'),  3, 1.00, 1, '2026-01-01', NOW()),
  ((SELECT id FROM currencies WHERE code='CHF'),  6, 1.25, 1, '2026-01-01', NOW()),
  ((SELECT id FROM currencies WHERE code='CHF'), 12, 1.50, 1, '2026-01-01', NOW()),
  ((SELECT id FROM currencies WHERE code='CHF'), 24, 1.75, 1, '2026-01-01', NOW()),
  ((SELECT id FROM currencies WHERE code='CHF'), 36, 2.00, 1, '2026-01-01', NOW()),

  ((SELECT id FROM currencies WHERE code='GBP'),  3, 1.75, 1, '2026-01-01', NOW()),
  ((SELECT id FROM currencies WHERE code='GBP'),  6, 2.00, 1, '2026-01-01', NOW()),
  ((SELECT id FROM currencies WHERE code='GBP'), 12, 2.50, 1, '2026-01-01', NOW()),
  ((SELECT id FROM currencies WHERE code='GBP'), 24, 3.00, 1, '2026-01-01', NOW()),
  ((SELECT id FROM currencies WHERE code='GBP'), 36, 3.25, 1, '2026-01-01', NOW()),

  ((SELECT id FROM currencies WHERE code='CAD'),  3, 1.50, 1, '2026-01-01', NOW()),
  ((SELECT id FROM currencies WHERE code='CAD'),  6, 1.75, 1, '2026-01-01', NOW()),
  ((SELECT id FROM currencies WHERE code='CAD'), 12, 2.25, 1, '2026-01-01', NOW()),
  ((SELECT id FROM currencies WHERE code='CAD'), 24, 2.75, 1, '2026-01-01', NOW()),
  ((SELECT id FROM currencies WHERE code='CAD'), 36, 3.00, 1, '2026-01-01', NOW()),

  ((SELECT id FROM currencies WHERE code='AUD'),  3, 1.75, 1, '2026-01-01', NOW()),
  ((SELECT id FROM currencies WHERE code='AUD'),  6, 2.00, 1, '2026-01-01', NOW()),
  ((SELECT id FROM currencies WHERE code='AUD'), 12, 2.50, 1, '2026-01-01', NOW()),
  ((SELECT id FROM currencies WHERE code='AUD'), 24, 3.00, 1, '2026-01-01', NOW()),
  ((SELECT id FROM currencies WHERE code='AUD'), 36, 3.25, 1, '2026-01-01', NOW()),

  ((SELECT id FROM currencies WHERE code='JPY'),  3, 0.50, 1, '2026-01-01', NOW()),
  ((SELECT id FROM currencies WHERE code='JPY'),  6, 1.00, 1, '2026-01-01', NOW()),
  ((SELECT id FROM currencies WHERE code='JPY'), 12, 1.50, 1, '2026-01-01', NOW()),
  ((SELECT id FROM currencies WHERE code='JPY'), 24, 2.00, 1, '2026-01-01', NOW()),
  ((SELECT id FROM currencies WHERE code='JPY'), 36, 2.50, 1, '2026-01-01', NOW());

SELECT setval('savings_interest_rates_id_seq', (SELECT COALESCE(MAX(id), 1) FROM savings_interest_rates));

-- Demo deposit-i za seedovane klijente:
-- Stefan (client_id=1): 200,000 RSD na 12 meseci, autoRenew=true, otvoren 2 meseca pre, vec 2 isplate kamate
--   linked_account: prvi aktivni RSD racun Stefana (account_number=222000112345678911)
-- Milica (client_id=2): 1,000 EUR na 6 meseci, autoRenew=false, otvoren 1 mesec pre, 1 isplata kamate
--   linked_account: prvi aktivni EUR racun Milice (account_number=222000121345678923)
-- Koristimo subquery umesto hard-coded account.id jer accounts tabela nema eksplicitne ID-eve u seed-u.

INSERT INTO savings_deposits (
    client_id, linked_account_id, principal_amount, currency_id, term_months,
    annual_interest_rate, start_date, maturity_date, next_interest_payment_date,
    total_interest_paid, auto_renew, status, version, created_at, updated_at
) VALUES
  (1,
   (SELECT id FROM accounts WHERE client_id=1 AND currency_id=(SELECT id FROM currencies WHERE code='RSD') AND status='ACTIVE' ORDER BY id LIMIT 1),
   200000.0000, (SELECT id FROM currencies WHERE code='RSD'), 12,
   4.00, '2026-03-12', '2027-03-12', '2026-06-12',
   1333.3333, 1, 'ACTIVE', 0, NOW(), NOW()),
  (2,
   (SELECT id FROM accounts WHERE client_id=2 AND currency_id=(SELECT id FROM currencies WHERE code='EUR') AND status='ACTIVE' ORDER BY id LIMIT 1),
   1000.0000, (SELECT id FROM currencies WHERE code='EUR'), 6,
   2.00, '2026-04-12', '2026-10-12', '2026-06-12',
   1.6667, 0, 'ACTIVE', 0, NOW(), NOW());

SELECT setval('savings_deposits_id_seq', (SELECT COALESCE(MAX(id), 1) FROM savings_deposits));

-- Istorija transakcija za demo deposit-e
-- deposit_id=1 = Stefanov RSD deposit, deposit_id=2 = Milicin EUR deposit
-- (Ovi ID-evi su sigurni jer su ovo prvi INSERT-i u savings_deposits tabelu)
INSERT INTO savings_transactions (deposit_id, type, amount, currency_id, processed_date, description, created_at) VALUES
  (1, 'OPEN', 200000.0000, (SELECT id FROM currencies WHERE code='RSD'),
   '2026-03-12', 'Otvaranje depozita rok=12m stopa=4.00% p.a.', '2026-03-12 09:00:00'),
  (1, 'INTEREST_PAYMENT', 666.6667, (SELECT id FROM currencies WHERE code='RSD'),
   '2026-04-12', 'Mesecna kamata depozita #1', '2026-04-12 02:00:00'),
  (1, 'INTEREST_PAYMENT', 666.6667, (SELECT id FROM currencies WHERE code='RSD'),
   '2026-05-12', 'Mesecna kamata depozita #1', '2026-05-12 02:00:00'),
  (2, 'OPEN', 1000.0000, (SELECT id FROM currencies WHERE code='EUR'),
   '2026-04-12', 'Otvaranje depozita rok=6m stopa=2.00% p.a.', '2026-04-12 10:30:00'),
  (2, 'INTEREST_PAYMENT', 1.6667, (SELECT id FROM currencies WHERE code='EUR'),
   '2026-05-12', 'Mesecna kamata depozita #2', '2026-05-12 02:00:00');

SELECT setval('savings_transactions_id_seq', (SELECT COALESCE(MAX(id), 1) FROM savings_transactions));

-- ============================================================
-- 14.05.2026 vece-4: Branches (ekspoziture + bankomati) — mapa Beograda
-- 12 BRANCH + 60 ATM po opstinama centralnog Beograda (Stari Grad, Vracar,
-- Novi Beograd, Zvezdara, Vozdovac, Zemun, Palilula, Cukarica, Savski Venac).
-- 8 ATM sa has_24h=1, 6 sa has_drive_through=1.
-- ============================================================

INSERT INTO branches (name, type, address, latitude, longitude, opening_hours, has_24h, has_drive_through, created_at) VALUES
  ('Banka 2 - Knez Mihailova', 'BRANCH', 'Knez Mihailova 22, Stari Grad', 44.816512, 20.456789, '08-16 radnim danima', 0, 0, NOW()),
  ('Banka 2 - Slavija', 'BRANCH', 'Trg Slavija 2, Vracar', 44.802345, 20.466789, '08-17 radnim danima, 09-13 subotom', 0, 0, NOW()),
  ('Banka 2 - Novi Beograd Blok 19', 'BRANCH', 'Bulevar Mihaila Pupina 165, Novi Beograd', 44.812345, 20.421234, '08-16 radnim danima', 0, 0, NOW()),
  ('Banka 2 - Zvezdara', 'BRANCH', 'Bulevar Kralja Aleksandra 226, Zvezdara', 44.792345, 20.502345, '08-16 radnim danima', 0, 0, NOW()),
  ('Banka 2 - Vozdovac', 'BRANCH', 'Vojvode Stepe 380, Vozdovac', 44.770123, 20.485678, '08-16 radnim danima', 0, 0, NOW()),
  ('Banka 2 - Zemun', 'BRANCH', 'Glavna 42, Zemun', 44.843456, 20.401234, '08-17 radnim danima', 0, 0, NOW()),
  ('Banka 2 - Palilula', 'BRANCH', 'Cara Dusana 132, Palilula', 44.825678, 20.474567, '08-16 radnim danima', 0, 0, NOW()),
  ('Banka 2 - Cukarica', 'BRANCH', 'Pozeska 92, Cukarica', 44.760234, 20.421567, '08-16 radnim danima', 0, 0, NOW()),
  ('Banka 2 - Savski Venac', 'BRANCH', 'Bircaninova 18, Savski Venac', 44.798456, 20.447890, '08-17 radnim danima', 0, 0, NOW()),
  ('Banka 2 - Vracar Cubura', 'BRANCH', 'Maksima Gorkog 56, Vracar', 44.792789, 20.476543, '08-16 radnim danima', 0, 0, NOW()),
  ('Banka 2 - Novi Beograd Arena', 'BRANCH', 'Bulevar Arsenija Carnojevica 132, Novi Beograd', 44.808901, 20.410234, '08-17 radnim danima', 0, 0, NOW()),
  ('Banka 2 - Banjica', 'BRANCH', 'Crnotravska 27, Vozdovac', 44.760456, 20.480234, '08-16 radnim danima', 0, 0, NOW()),
  ('ATM Knez Mihailova', 'ATM', 'Knez Mihailova 15', 44.816000, 20.456500, '00-24', 1, 0, NOW()),
  ('ATM Slavija', 'ATM', 'Trg Slavija 4', 44.802500, 20.466500, '00-24', 1, 0, NOW()),
  ('ATM Trg Republike', 'ATM', 'Trg Republike 5', 44.816800, 20.460700, '00-24', 1, 0, NOW()),
  ('ATM Terazije', 'ATM', 'Terazije 23', 44.812500, 20.460100, '00-24', 1, 0, NOW()),
  ('ATM Skadarska', 'ATM', 'Skadarska 29', 44.818100, 20.463200, '07-22', 0, 0, NOW()),
  ('ATM Studentski Trg', 'ATM', 'Studentski Trg 13', 44.819500, 20.458300, '07-22', 0, 0, NOW()),
  ('ATM Vracar Cubura', 'ATM', 'Maksima Gorkog 89', 44.793100, 20.477200, '07-22', 0, 0, NOW()),
  ('ATM Vracar Krunska', 'ATM', 'Krunska 56', 44.806200, 20.470900, '07-22', 0, 0, NOW()),
  ('ATM Vracar Kalenic', 'ATM', 'Kalenicka 15', 44.795200, 20.468500, '07-22', 0, 0, NOW()),
  ('ATM Vracar Cara Nikolaja', 'ATM', 'Cara Nikolaja II 41', 44.798900, 20.473300, '00-24', 1, 0, NOW()),
  ('ATM Novi Beograd Blok 21', 'ATM', 'Jurija Gagarina 165', 44.808100, 20.401500, '07-22', 0, 0, NOW()),
  ('ATM Novi Beograd Blok 23', 'ATM', 'Antifasisticke borbe 4', 44.813800, 20.412600, '07-22', 0, 0, NOW()),
  ('ATM Novi Beograd Blok 45', 'ATM', 'Jurija Gagarina 95', 44.795700, 20.388900, '07-22', 0, 0, NOW()),
  ('ATM Bul. Mihaila Pupina', 'ATM', 'Bulevar Mihaila Pupina 117', 44.813900, 20.418700, '00-24', 1, 0, NOW()),
  ('ATM Novi Beograd Arena', 'ATM', 'Bulevar Arsenija Carnojevica 58', 44.807100, 20.415300, '00-24', 1, 1, NOW()),
  ('ATM Novi Beograd Belvil', 'ATM', 'Jurija Gagarina 12', 44.802300, 20.395800, '07-22', 0, 0, NOW()),
  ('ATM Novi Beograd Park', 'ATM', 'Bulevar Mihaila Pupina 6c', 44.811700, 20.426400, '07-22', 0, 1, NOW()),
  ('ATM Zemun Glavna', 'ATM', 'Glavna 24', 44.843100, 20.401900, '07-22', 0, 0, NOW()),
  ('ATM Zemun Magistratski', 'ATM', 'Magistratski Trg 1', 44.843800, 20.402600, '07-22', 0, 0, NOW()),
  ('ATM Zemun Kej', 'ATM', 'Kej Oslobodjenja 31', 44.847200, 20.408400, '00-24', 1, 0, NOW()),
  ('ATM Zemun Mercator', 'ATM', 'Auto-Put 6', 44.848600, 20.385700, '07-22', 0, 1, NOW()),
  ('ATM Zemun Gardos', 'ATM', 'Velikih Stepenica 22', 44.847900, 20.401100, '07-22', 0, 0, NOW()),
  ('ATM Vozdovac Banjica', 'ATM', 'Crnotravska 17', 44.762100, 20.479800, '07-22', 0, 0, NOW()),
  ('ATM Vozdovac Centar', 'ATM', 'Vojvode Stepe 350', 44.770900, 20.486100, '07-22', 0, 0, NOW()),
  ('ATM Vozdovac Lekino Brdo', 'ATM', 'Mostarska 40', 44.776300, 20.480500, '07-22', 0, 0, NOW()),
  ('ATM Vozdovac Jajinci', 'ATM', 'Bulevar Jugoslovenske Armije 109', 44.747800, 20.473200, '07-22', 0, 0, NOW()),
  ('ATM Vozdovac Konjarnik', 'ATM', 'Ustanicka 100', 44.787500, 20.499300, '07-22', 0, 0, NOW()),
  ('ATM Cvetkova Pijaca', 'ATM', 'Bulevar Kralja Aleksandra 235', 44.792800, 20.502900, '00-24', 1, 0, NOW()),
  ('ATM Mirijevo', 'ATM', 'Vojislava Ilica 88', 44.796500, 20.524800, '07-22', 0, 0, NOW()),
  ('ATM Profesorska Kolonija', 'ATM', 'Bulevar Kralja Aleksandra 73', 44.799100, 20.495600, '07-22', 0, 0, NOW()),
  ('ATM Vukov Spomenik', 'ATM', 'Bulevar Kralja Aleksandra 56', 44.806300, 20.485900, '07-22', 0, 0, NOW()),
  ('ATM Banovo Brdo', 'ATM', 'Pozeska 90', 44.760100, 20.420800, '07-22', 0, 0, NOW()),
  ('ATM Filmski Grad', 'ATM', 'Kneza Viseslava 88', 44.755400, 20.401700, '07-22', 0, 1, NOW()),
  ('ATM Zarkovo', 'ATM', 'Trgovacka 130', 44.768200, 20.413900, '07-22', 0, 0, NOW()),
  ('ATM Vidikovac', 'ATM', 'Jablanicka 12', 44.747600, 20.402100, '07-22', 0, 1, NOW()),
  ('ATM Karaburma', 'ATM', 'Mije Kovacevica 7', 44.822700, 20.499500, '07-22', 0, 0, NOW()),
  ('ATM Borca', 'ATM', 'Zrenjaninski Put 24', 44.864900, 20.479300, '07-22', 0, 0, NOW()),
  ('ATM Hadzipopovac', 'ATM', 'Cara Dusana 92', 44.825300, 20.471800, '07-22', 0, 0, NOW()),
  ('ATM Visnjicka Banja', 'ATM', 'Visnjicka 70', 44.832600, 20.497400, '07-22', 0, 0, NOW()),
  ('ATM Senjak', 'ATM', 'Bircaninova 15', 44.798300, 20.447600, '07-22', 0, 0, NOW()),
  ('ATM Belvedere', 'ATM', 'Tolstojeva 51', 44.790100, 20.448500, '07-22', 0, 0, NOW()),
  ('ATM Dedinje', 'ATM', 'Uzicka 11', 44.781700, 20.456800, '07-22', 0, 0, NOW()),
  ('ATM Bul. Oslobodjenja', 'ATM', 'Bulevar Oslobodjenja 7', 44.800300, 20.463900, '07-22', 0, 0, NOW()),
  ('ATM Dorcol', 'ATM', 'Cara Dusana 39', 44.823800, 20.461700, '07-22', 0, 0, NOW()),
  ('ATM Pancicev Park', 'ATM', 'Vase Carapica 11', 44.819100, 20.461500, '07-22', 0, 0, NOW()),
  ('ATM Beton Hala', 'ATM', 'Karadjordjeva 2-4', 44.819900, 20.452500, '00-24', 1, 0, NOW()),
  ('ATM Kalemegdan', 'ATM', 'Pariska 12', 44.821100, 20.452900, '07-22', 0, 0, NOW()),
  ('ATM Tasmajdan', 'ATM', 'Beogradska 23', 44.806800, 20.466500, '07-22', 0, 0, NOW()),
  ('ATM Cumicevo Sokace', 'ATM', 'Njegoseva 4', 44.808900, 20.469900, '07-22', 0, 0, NOW()),
  ('ATM Pancevo Bridge', 'ATM', 'Pancevacki Put 12', 44.827500, 20.488600, '07-22', 0, 0, NOW()),
  ('ATM Sava Centar', 'ATM', 'Bulevar Mihaila Pupina 32', 44.811200, 20.430700, '07-22', 0, 0, NOW()),
  ('ATM Hotel Sava', 'ATM', 'Bulevar Mihaila Pupina 9', 44.811100, 20.431100, '07-22', 0, 0, NOW()),
  ('ATM Jurija Gagarina 36', 'ATM', 'Jurija Gagarina 36', 44.799500, 20.395100, '07-22', 0, 0, NOW()),
  ('ATM Studentski Grad', 'ATM', 'Tosin Bunar 143', 44.823900, 20.405400, '07-22', 0, 0, NOW()),
  ('ATM Bezanijska Kosa', 'ATM', 'Partizanske Avijacije 4', 44.833100, 20.391800, '07-22', 0, 0, NOW()),
  ('ATM Skola Kralja Petra', 'ATM', 'Krunska 8', 44.805900, 20.466300, '07-22', 0, 0, NOW()),
  ('ATM Marina Drzica', 'ATM', 'Marina Drzica 25', 44.768800, 20.474500, '07-22', 0, 0, NOW()),
  ('ATM Marsala Tita', 'ATM', 'Marsala Tita 50', 44.844800, 20.402300, '07-22', 0, 0, NOW()),
  ('ATM Lipov Lad', 'ATM', 'Mileseva 14', 44.800800, 20.510200, '07-22', 0, 0, NOW());

SELECT setval('branches_id_seq', (SELECT COALESCE(MAX(id), 1) FROM branches));

-- ============================================================
-- 14.05.2026 vece-5: Game scores za Sobu za cekanje (leaderboard demo)
-- ============================================================
INSERT INTO game_scores (client_id, player_name, game_type, score, created_at) VALUES
-- DINO highscore (distance)
(1, 'Stefan Jovanović', 'DINO', 4250, NOW() - INTERVAL '3 days'),
(2, 'Milica Nikolić', 'DINO', 3890, NOW() - INTERVAL '2 days'),
(3, 'Lazar Ilić', 'DINO', 5120, NOW() - INTERVAL '1 days'),
(4, 'Ana Stojanović', 'DINO', 2780, NOW() - INTERVAL '6 hours'),
(1, 'Stefan Jovanović', 'DINO', 6810, NOW() - INTERVAL '1 hours'),
-- SOLITAIRE wins (vise pobeda je bolje za leaderboard rank)
(2, 'Milica Nikolić', 'SOLITAIRE', 12, NOW() - INTERVAL '5 days'),
(1, 'Stefan Jovanović', 'SOLITAIRE', 8, NOW() - INTERVAL '4 days'),
(3, 'Lazar Ilić', 'SOLITAIRE', 5, NOW() - INTERVAL '3 days'),
(4, 'Ana Stojanović', 'SOLITAIRE', 3, NOW() - INTERVAL '2 days'),
-- CHESS wins
(3, 'Lazar Ilić', 'CHESS', 7, NOW() - INTERVAL '4 days'),
(1, 'Stefan Jovanović', 'CHESS', 4, NOW() - INTERVAL '3 days'),
(2, 'Milica Nikolić', 'CHESS', 2, NOW() - INTERVAL '2 days'),
-- BANKA2_RUSH high score (distance + coins)
(1, 'Stefan Jovanović', 'BANKA2_RUSH', 12450, NOW() - INTERVAL '2 days'),
(4, 'Ana Stojanović', 'BANKA2_RUSH', 9870, NOW() - INTERVAL '1 days'),
(2, 'Milica Nikolić', 'BANKA2_RUSH', 15630, NOW() - INTERVAL '12 hours'),
(3, 'Lazar Ilić', 'BANKA2_RUSH', 8240, NOW() - INTERVAL '4 hours');

SELECT setval('game_scores_id_seq', (SELECT COALESCE(MAX(id), 1) FROM game_scores));
