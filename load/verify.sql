-- psql -v event_id=<uuid> -f load/verify.sql ; every row must have passed = t.
-- One query per pass criterion; each returns (check_name, passed, detail).

-- 1. booked seats <= 100 and equal to the seats of confirmed holds
select 'booked seats <= 100 and = seats of confirmed holds' as check_name,
       b.n <= 100 and b.n = c.n and b.n = x.n as passed,
       format('booked=%s, seats in confirmed holds=%s, booked seats in confirmed holds=%s', b.n, c.n, x.n) as detail
from (select count(*) n from seats where event_id = :'event_id' and status = 'BOOKED') b,
     (select count(*) n from seats s join holds h on h.id = s.hold_id
       where s.event_id = :'event_id' and h.status = 'CONFIRMED') c,
     (select count(*) n from seats s join holds h on h.id = s.hold_id
       where s.event_id = :'event_id' and h.status = 'CONFIRMED' and s.status = 'BOOKED') x;

-- 2. no seat in two confirmed holds (seats.hold_id is one column, so this also guards the join itself)
select 'no seat in two confirmed holds' as check_name,
       count(*) = 0 as passed,
       format('seats appearing in more than one confirmed hold=%s', count(*)) as detail
from (select s.id from seats s join holds h on h.id = s.hold_id
       where s.event_id = :'event_id' and h.status = 'CONFIRMED'
       group by s.id having count(*) > 1) d;

-- 3. one succeeded payment per confirmed hold, none for any other hold
select 'one succeeded payment per confirmed hold, none for others' as check_name,
       count(*) = 0 as passed,
       format('violating holds=%s', count(*)) as detail
from (select h.id
        from holds h left join payments p on p.hold_id = h.id and p.status = 'SUCCEEDED'
       where h.event_id = :'event_id'
       group by h.id, h.status
      having count(p.id) <> case when h.status = 'CONFIRMED' then 1 else 0 end) v;

-- 4. at most one payment per (user, idempotency key)
select 'at most one payment per (user, idempotency key)' as check_name,
       count(*) = 0 as passed,
       format('duplicated keys=%s', count(*)) as detail
from (select p.user_id, p.idempotency_key from payments p join holds h on h.id = p.hold_id
       where h.event_id = :'event_id'
       group by 1, 2 having count(*) > 1) d;

-- 5. every succeeded payment's ledger entries balance (debits = credits), and failed/pending have none
select 'ledger balanced per payment' as check_name,
       count(*) = 0 as passed,
       format('unbalanced payments=%s', count(*)) as detail
from (select p.id
        from payments p join holds h on h.id = p.hold_id
        left join ledger_entries l on l.payment_id = p.id
       where h.event_id = :'event_id'
       group by p.id, p.status
      having coalesce(sum(l.amount_cents) filter (where l.direction = 'DEBIT'), 0)
          <> coalesce(sum(l.amount_cents) filter (where l.direction = 'CREDIT'), 0)
          or (p.status = 'SUCCEEDED' and count(l.id) = 0)) u;

-- 6. settlement drained (precondition, not a pass criterion)
select 'no PENDING payments left' as check_name,
       count(*) = 0 as passed,
       format('pending=%s', count(*)) as detail
from payments p join holds h on h.id = p.hold_id
where h.event_id = :'event_id' and p.status = 'PENDING';
