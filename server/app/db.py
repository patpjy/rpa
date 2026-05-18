"""SQLite + SQLAlchemy setup."""
from __future__ import annotations

from pathlib import Path

from sqlalchemy import create_engine, text
from sqlalchemy.orm import DeclarativeBase, sessionmaker

DB_PATH = Path(__file__).parent.parent / "dyrpa.db"

engine = create_engine(
    f"sqlite:///{DB_PATH}",
    connect_args={"check_same_thread": False},
    future=True,
)
SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine, future=True)


class Base(DeclarativeBase):
    pass


def init_db() -> None:
    from . import models  # noqa: F401 — register tables

    Base.metadata.create_all(bind=engine)
    # Idempotent column additions for live databases. SQLite's ALTER TABLE
    # cannot DROP/MODIFY but ADD COLUMN works. Each statement here must be
    # safe to re-run on already-migrated DBs.
    _add_column_if_missing("tasks", "last_event_at", "REAL DEFAULT 0.0")


def _add_column_if_missing(table: str, column: str, decl: str) -> None:
    with engine.connect() as conn:
        try:
            conn.execute(text(f"ALTER TABLE {table} ADD COLUMN {column} {decl}"))
            conn.commit()
        except Exception:
            pass  # column already exists or table missing — both safe to ignore


def get_session():
    """FastAPI dependency yielding a Session."""
    session = SessionLocal()
    try:
        yield session
    finally:
        session.close()
