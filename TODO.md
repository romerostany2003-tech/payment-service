Deferred items (we’ll come back to these)
    1. Docker Compose for the database
    2. Testcontainers integration test (it needs Docker, so it waits)
    3. Reconsidering MySQL vs PostgreSQL, if you decide to


MySQL vs PostgreSQL Default Isolation Levels and Their Impact

Where will the idempotency key eventually live: on the payments table or in its own table? You don’t need to build it now, but your table design shouldn’t box you in.



1.replay returns the original response
2.foreign key on payment_id
3.status stays PENDING until later steps
4.idempotency records never expire