| Strategy | Scenario | Succeeded | Failed | Retries | Time (ms) | Final state | Correct |
|---|---|---|---|---|---|---|---|
| NO_LOCKING | 50 x withdraw 100 from 1,000 | 50 | 0 | 0 | 222 | balance 800.00 after 50 withdrawals | **NO** |
| OPTIMISTIC | 50 x withdraw 100 from 1,000 | 2 | 48 | 0 | 314 | balance 800.00 after 2 withdrawals | **NO** |
| OPTIMISTIC_RETRY | 50 x withdraw 100 from 1,000 | 10 | 40 | 342 | 841 | balance 0.00 after 10 withdrawals | yes |
| PESSIMISTIC | 50 x withdraw 100 from 1,000 | 10 | 40 | 0 | 231 | balance 0.00 after 10 withdrawals | yes |
| PRODUCTION_PATH | 50 x withdraw 100 from 1,000 | 10 | 40 | 0 | 1376 | balance 0.00 after 10 withdrawals | yes |
| NO_LOCKING | 1,000 opposing transfers | 565 | 435 | 0 | 3902 | total 19843.00 (must be 20000.00) | **NO** |
| OPTIMISTIC | 1,000 opposing transfers | 55 | 945 | 0 | 1237 | total 20000.00 (must be 20000.00) | yes |
| OPTIMISTIC_RETRY | 1,000 opposing transfers | 969 | 31 | 23670 | 17099 | total 20000.00 (must be 20000.00) | yes |
| PESSIMISTIC | 1,000 opposing transfers | 1000 | 0 | 0 | 8829 | total 20000.00 (must be 20000.00) | yes |
| PRODUCTION_PATH | 1,000 opposing transfers | 1000 | 0 | 0 | 29257 | total 20000.00 (must be 20000.00) | yes |
