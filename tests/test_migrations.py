"""Execute extracted production CREATE/ALTER statements against actual SQLite.
Adapters intentionally do not pretend to test Android's SQLite implementation.
"""
import re, sqlite3, ast
from pathlib import Path
root=Path(__file__).resolve().parents[1]
def strings(text):
    return [ast.literal_eval(x) for x in re.findall(r'"(?:[^"\\]|\\.)*"',text)]
def creates(src):
    for call in re.findall(r'db\.execSQL\((.*?)\);',src,re.S):
        q=''.join(strings(call))
        if q.startswith('CREATE '):yield q
journal=(root/'src/com/falcon/quantum/Journal.java').read_text()
paper=(root/'src/com/falcon/quantum/Paper.java').read_text()
checks=0
for name,src in [('journal',journal),('paper',paper)]:
    db=sqlite3.connect(':memory:')
    for q in creates(src):db.execute(q)
    if name=='journal':
        cols=strings(src.split('String[] columns = {',1)[1].split('};',1)[0])
        db.execute("INSERT INTO trades(id,env,symbol,side,pnl,status) VALUES(7,'DEMO','BTCUSDT','LONG',-5,'CLOSED')")
    else:
        cols=strings(src.split('for(String col:new String[]{',1)[1].split('}) db.execSQL',1)[0])
        db.execute("INSERT INTO trades(id,env,symbol,side,pnl,status) VALUES(7,'PAPER','BTCUSDT','LONG',-5,'STOP')")
    for col in cols:db.execute('ALTER TABLE trades ADD COLUMN '+col)
    assert db.execute('SELECT id,pnl FROM trades').fetchone()==(7,-5);checks+=1
    assert 'DROP TABLE' not in src;checks+=1
    if name=='journal':
        assert db.execute('SELECT cost_state FROM trades').fetchone()[0]=='LEGACY_UNVERIFIED';checks+=1
        # History SELECT must reference valid migrated columns in the production order.
        raw=src.split('public List<Trade> history',1)[1].split('while (c.moveToNext())',1)[0]
        q=''.join(strings(raw.split('rawQuery(',1)[1].split('Math.max',1)[0]))+'10'
        db.execute(q,('DEMO',)).fetchall();checks+=1
    else:
        # Closing must compare-and-set OPEN, so two workers cannot credit margin twice.
        db.execute("INSERT INTO account(env,balance) VALUES('PAPER',900)")
        db.execute("UPDATE trades SET status='OPEN' WHERE id=7")
        for _ in range(2):
            changed=db.execute("UPDATE trades SET status='STOP' WHERE id=7 AND status='OPEN'").rowcount
            if changed:db.execute("UPDATE account SET balance=balance+90 WHERE env='PAPER'")
        assert db.execute("SELECT balance FROM account WHERE env='PAPER'").fetchone()[0]==990;checks+=1
print(f'PASS {checks} SQLite migration/idempotency assertions')
