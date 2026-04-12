import os
import sqlite3

db = r"C:\AIROS code clean\ravintola_backend\data\airos_pos.sqlite3"

print("AIROS_POS_DB_PATH =", os.getenv("AIROS_POS_DB_PATH"))
print("AIROS_POS_SCHEMA_PATH =", os.getenv("AIROS_POS_SCHEMA_PATH"))
print("DB EXISTS =", os.path.exists(db))
if os.path.exists(db):
    print("DB SIZE =", os.path.getsize(db))

con = sqlite3.connect(db)
try:
    print("\nTABLES:")
    rows = con.execute("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name").fetchall()
    for r in rows:
        print("-", r[0])

    print("\nSALE_FINALIZATIONS COLUMNS:")
    try:
        cols = con.execute("PRAGMA table_info(sale_finalizations)").fetchall()
        for c in cols:
            print(c)
    except Exception as e:
        print("ERROR:", e)
finally:
    con.close()
