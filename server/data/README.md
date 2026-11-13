# Project datasets

| Dataset | Location | What we use it for |
| --- | --- | --- |
| Digital Matatus Nairobi network, 2019 release | [Real dataset](external/digital-matatus-2019/README.md) | Stop coordinates, named stages, routes and mapped connectivity; network cleaning and analysis. |
| Nairobi CBD synthetic arrivals, v1 | [Arrival bundle](examples/nairobi-cbd-synthetic-v1.json) | Train and evaluate the waiting-time QRF through known, dated arrival sequences. |

These are separate datasets with different roles. The real network feed supplies
geographic and service context. The arrival bundle supplies the current model's
training examples. The reported MAE and R² still belong to the arrival experiment.

A real arrival-training dataset needs repeated observations at the same stage,
route and direction, with dates, time zones and continuous collection boundaries
across several days. The network archive does not contain that evidence. It stays
outside the admin's observation importer, whose `field` classification is reserved
for declared field observations rather than static schedule records.
