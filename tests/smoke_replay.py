"""End-to-end wiring check on SYNTHETIC prices. Never performance evidence."""
import csv
import json
import math
import random
import subprocess
import tempfile
from pathlib import Path

rng = random.Random(7421)
root = Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory(prefix='falcon-synthetic-') as tmp:
    base = Path(tmp)
    price = 100.0
    with (base/'contract.csv').open('w') as cf, (base/'mark.csv').open('w') as mf:
        cw,mw=csv.writer(cf),csv.writer(mf)
        header=['open_time','open','high','low','close','volume']
        cw.writerow(header);mw.writerow(header)
        for i in range(30*1440):
            op=price
            drift=.00004 if (i//960)%2==0 else -.00004
            price=op*math.exp(drift+rng.gauss(0,.0030))
            hi=max(op,price)*1.0008;lo=min(op,price)*.9992
            vol=100000*(1+.35*math.sin(i/50))
            cw.writerow([i*60000,op,hi,lo,price,vol])
            mw.writerow([i*60000,op*1.00002,hi*1.00002,lo*1.00002,price*1.00002,0])
    with (base/'funding.csv').open('w') as f:
        w=csv.writer(f);w.writerow(['funding_time','funding_rate','mark_price'])
        for hour in range(0,30*24,8):w.writerow([hour*3600000+1,.0001,100])
    command=['java','-cp',str(root/'.test-build'),'com.falcon.quantum.BacktestMain',
             '--contract',str(base/'contract.csv'),'--mark',str(base/'mark.csv'),
             '--funding',str(base/'funding.csv'),'--symbol','TESTUSDT']
    checks=0
    total_trades=0
    for stage,expected,extra in [('development',32,[]),('holdout',2,['--entry','BASELINE','--exit','FIXED'])]:
        out=base/stage
        subprocess.run(command+['--out',str(out),'--stage',stage]+extra,check=True)
        with (out/'comparison.csv').open() as f:rows=list(csv.DictReader(f))
        assert len(rows)==expected;checks+=1
        print('Synthetic execution coverage:', sum(int(x['trades']) for x in rows), 'trades across scenarios;', sum(int(x['skipped_risk']) for x in rows), 'risk refusals')
        assert all((r['sample']=='holdout')==(stage=='holdout') for r in rows);checks+=1
        assert json.loads((out/'provenance.json').read_text())['live_approved'] is False;checks+=1
        for report in rows:
            filename=f"{report['sample']}-{report['entry']}-{report['exit']}-cost{report['cost_scenario']}-trades.csv"
            with (out/filename).open() as f:trades=list(csv.DictReader(f))
            assert len(trades)==int(report['trades'])
            total_trades += len(trades)
            net=0
            for t in trades:
                assert abs(float(t['gross'])-float(t['fees'])+float(t['funding'])-float(t['net']))<1e-6
                assert int(t['exit_time'])>=int(t['entry_time'])
                net+=float(t['net'])
            assert abs(net-float(report['net_pnl']))<1e-5
        checks+=1
    assert total_trades>0, "Synthetic fixture must exercise fills, not just empty reports"
    checks+=1
    # Unknown arguments must fail before reading data or producing misleading results.
    result=subprocess.run(command+['--out',str(base/'bad'),'--risk-perct','50'],capture_output=True)
    assert result.returncode!=0;checks+=1
    print(f'PASS {checks} replay CLI/wallet checks on SYNTHETIC data only; no performance claim')
