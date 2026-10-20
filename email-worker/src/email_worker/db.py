from contextlib import contextmanager


@contextmanager
def connect(database_url: str):
    import psycopg
    with psycopg.connect(database_url, connect_timeout=10, autocommit=False) as conn:
        with conn.cursor() as cur:
            cur.execute("SET statement_timeout = '10s'")
            cur.execute("SET lock_timeout = '3s'")
        yield conn

