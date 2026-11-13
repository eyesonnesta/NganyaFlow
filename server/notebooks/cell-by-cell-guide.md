# NganyaFlow notebook: a guide to each code cell

[Open the explained notebook](02_data_cleaning_and_model_evaluation_explained.ipynb).

## Start here: the question we want to answer

A passenger arrives at Kencom and asks, "How long until the next vehicle?"
NganyaFlow needs to turn earlier arrival records into a useful estimate.
In this notebook, we check those records, teach a model using earlier days,
then see how well it predicts waits on later days.

An arrival time and a passenger's waiting time are different. If vehicles arrive
at 7:00, 7:06 and 7:14, someone asking at 7:08 waits 6 minutes. At that moment,
we know the last gap was 6 minutes and that 2 minutes have passed since the
latest arrival. We only learn the correct answer, 6 minutes, once the 7:14
arrival happens. Recorded history lets us create examples like this to train
and test the model.

The numbered explanations below follow the 15 code cells in reading order.
Jupyter's bracketed execution numbers can differ if you rerun individual cells.
The existing technical notes give more detail after you understand each step.

A few Python terms will help:

- A **variable**, such as `cleaned`, is a name for something the code stores.
- A **function**, such as `clean_arrivals(...)`, is a reusable set of instructions.
- `import` loads a tool that Python can use. pandas handles tables, NumPy does
  numerical calculations, and Matplotlib draws charts.
- A pandas **DataFrame** is a table with rows and columns. A **Series** is one
  column or a labelled list of values.
- `display(...)` shows a table; `print(...)` shows a message. `assert` checks a
  condition and stops the cell if it fails. An assertion checks a particular
  assumption; it is not proof that every part of a dataset is correct.
- **JSON** stores structured records, while **CSV** stores a table that you can
  also open in a spreadsheet. Saving a file preserves it for later use.

### Code cell 1: prepare the tools

We first give Python access to the tools needed for the study. pandas will
organize the arrival records, NumPy will calculate errors, and Matplotlib
will draw the graphs. The cell also finds the project folder and creates a
place to save results.

The package-version table records which editions of those tools were used.
The interpreter path tells us which Python installation is running. SHA256
is a file fingerprint: comparing it before and after the study helps us check
that the input file has not changed. It does not tell us whether the data is
truthful or high quality.

No learning happens here. We are setting up the workspace so the remaining
steps use the intended tools and dataset.

### Code cell 2: read the records and connect them to their sessions

An **observation** records an event such as one vehicle arriving. A **session**
is a continuous period during which an enumerator watches a particular stage,
route and direction. The session tells us where an observation belongs and
when collection started and ended.

This cell opens the dataset and the **catalog**, the list of supported stages
and services. It uses the server's checks, then joins observations to their
sessions so each table row has both the arrival and its context. Many arrivals
may belong to one session; each arrival must have one matching session.

Look for 1,049 observations and 84 sessions. `missing` counts empty entries;
`unique` counts distinct values; `dtype` describes how Python stores a column,
such as text or numbers. **Provenance** simply means where the dataset came
from. These checks help us avoid learning from records whose time or location
we cannot interpret.

### Code cell 3: check which arrivals are usable

A repeated upload must not make the model believe two vehicles arrived when
only one did. An arrival recorded after its observation session ended also
needs attention. This cell defines the cleaning rules, applies them, and
separates accepted records from rejected ones.

A **timestamp** is a date and time. UTC gives us a common time reference;
Nairobi local time is used later when grouping records by day. A timestamp
without a time zone is ambiguous, so this code rejects it instead of guessing.
**Deduplication** means keeping one copy of a repeated record. Conflicting
versions are set aside with an explanation.

The **audit** is the count of accepted rows and rejection reasons. Here it
should show 1,049 accepted arrivals and zero rejections. Cleaning can confirm
that records already pass the rules; it does not have to remove something.
CSV files preserve the cleaned table and audit for review.

### Code cell 4: test the cleaning rules with known mistakes

Since the source passes the checks, we make a separate copy with seven added
problems. These include missing or invalid times, a duplicate, an unknown
stage and an arrival outside its session. Knowing which problems we inserted
gives us a way to check whether the cleaning function catches them.

Read the rejection table's `reason` column to see why each added row was
removed. The assertions check that all seven were rejected and that the
original accepted arrival IDs remain.

This resembles checking a mobile synchronization workflow: a retry may resend
an event, but it should still count once. The example checks these seven cases;
it does not test every possible mistake. This copy never enters training.

### Code cell 5: understand the gaps between vehicles

**Headway** means the time between consecutive vehicle arrivals. If two
vehicles reach Odeon at 7:00 and 7:06, their headway is 6 minutes. This cell
calculates gaps within each session, then summarizes the records by stage.
It never treats an overnight break between sessions as a vehicle gap.

The bar chart shows how many arrivals each stage contributes. The histogram
groups headways into ranges: taller bars mean more gaps fall in that range.
The first arrival in each session has no preceding arrival to compare with,
so its headway is blank.

In the summary, **median** is the middle value after sorting. A **percentile**
marks a position in that sorted list; roughly 90% of values are at or below
the 90th percentile. This helps us see whether most gaps are short, whether
some are much longer, and whether the stages have different patterns.

### Code cell 6: turn arrivals into passenger questions

Arrival records do not directly say how long someone asking between arrivals
would wait. The sample builder creates those questions at one-minute steps.
Each **sample** is one question, its available information, and the correct
waiting time obtained from the next recorded arrival.

**Features** are the clues given to the model: stage, route, direction, time
of day, weekday, time since the latest arrival, and the preceding headway.
The **target**, also called the label, is the wait we want it to predict.
Keeping the next arrival out of the features prevents **data leakage**, which
would give the model information unavailable when a passenger asks.

We keep 4,684 questions from 11 earlier dates for training and 1,259 questions
from the last 3 dates for testing. **Held out** means kept out of fitting.
One arrival can help create several minute-by-minute questions, which is why
there are more questions than arrivals. These questions are related, so we
keep complete dates and sessions together instead of randomly mixing rows.

### Code cell 7: teach the model to estimate a wait

**Training** means finding patterns between the clues and the known answers
in the earlier data. A **decision tree** repeatedly divides examples according
to their features, for example by elapsed time or preceding headway. A forest
combines many trees; ours has 100. Its minimum leaf size of 5 limits how small
the groups at the ends of the trees can become.

A **quantile regression forest** uses the waiting times represented in those
groups to estimate several positions in the range of possible waits. We ask
for a lower estimate, a middle estimate (the median), and an upper estimate.
The outer estimates form the proposed waiting-time range.

The random seed makes the training choices repeatable. **Encoding** converts
names such as stage IDs into numeric columns a model can use. An **artifact**
is the saved trained model and its supporting information; it is not a new
algorithm. This cell writes that file locally and reads the test predictions.
It does not replace the active server model or learn continuously by itself.

### Code cell 8: measure how far the predictions miss

We now compare predictions with known answers on the later test dates.
MAE averages the size of the mistakes: predicting 5 minutes when the wait is
3 minutes gives a 2-minute absolute error. Our MAE of about 0.939 minutes
means the average miss is about 56 seconds across these test questions.
It does not mean every prediction misses by 56 seconds.

**RMSE** is another error measure that gives extra weight to large mistakes.
**R²** compares squared prediction error with always predicting the test set's
average wait. Here it is about 0.844, or 84.4% of the variation accounted for.
It is not the percentage of correct predictions. The ±1 and ±2 minute columns
directly count predictions within those tolerances; about 90.39% are within
2 minutes.

A **baseline** is a simpler method used for comparison. One always uses the
historical median for that service; the other assumes the next gap will match
the last gap and subtracts time already elapsed. If these simple methods do
as well as the forest, its extra complexity may not help. Compare each row
using the same measure: lower MAE and RMSE are better.

### Code cell 9: check whether the predicted range is useful

NganyaFlow can show uncertainty alongside a single estimate. For example,
"about 5 minutes, with an estimated range of 3 to 7 minutes" gives a passenger
more information than a point estimate alone. Those numbers are an example;
the model calculates its own range for each query.

The **10th and 90th quantiles** are the model's lower and upper estimates.
The range between them aims to contain 80% of waits. **Coverage** is the share
of test answers actually inside those ranges. **Nominal** means the intended
coverage, 80%; **observed** means the measured result, about 80.06% here.
**Width** is upper minus lower, averaging about 3.10 minutes. A very wide
range may cover many answers while giving little useful guidance.

**Pinball loss** scores each quantile, penalizing errors differently depending
on which side of its intended boundary the actual wait falls. Lower is better.
Read coverage and width together; neither tells us the percentage of point
predictions that are correct.

### Code cell 10: check performance at each stage and on each day

One overall score can hide where passengers receive poorer estimates.
This cell groups the test questions first by stage and then by date,
calculating the number of questions, MAE and interval coverage for each group.

Kencom's MAE is about 0.873 minutes, Odeon's is 0.881, and Railways' is 1.073.
Their coverage figures also differ. This tells us where to investigate before
relying on the same model settings across all stages.

The coverage column is a fraction: 0.77 means about 77%. A stage with fewer
test questions contributes less evidence, so read the query counts alongside
the scores. Differences in this dataset do not by themselves explain what
causes the errors.

### Code cell 11: see the errors instead of only reading scores

The first figure shows three views of the same test results. Shorter bars
mean lower average errors. In the scatter plot, each dot is a question;
dots on the dashed diagonal have a predicted wait equal to the actual wait.
Dots farther from that line are larger misses.

The error histogram shows how mistakes are distributed. Positive values mean
we predicted a longer wait than occurred; negative values mean we predicted
a shorter wait. This distinction matters to a passenger deciding when to
reach a stage.

The second figure follows one Kencom session. The black line is the recorded
answer, the green line is the predicted median, and the shaded band is the
predicted range. Black-line points outside the band are waits the interval
missed. The horizontal axis counts query points within that session. Waits
fall as an arrival approaches, then rise again after a new arrival.

### Code cell 12: check what the model has learned

We compare normal training with training on **shuffled labels**, where answers
are randomly reassigned to the input questions. Shuffling breaks their useful
relationship. The normal model has MAE about 0.939 minutes, versus about 2.409
with shuffled answers. This supports the value of the patterns in our inputs.

The **learning curve** compares models trained on 3, 5 and 11 earlier dates,
all tested on the same later dates. Their test MAE falls from about 1.136 to
1.016 to 0.939 minutes. More training dates helped in this experiment; that
does not guarantee every future batch of data will improve the model.
Comparing training and test error can also help spot **overfitting**, where
a model fits familiar examples much better than new ones.

**Permutation importance** mixes up one feature at a time and measures how
much worse predictions become. A larger increase in MAE suggests the model
relies more on that feature. Time since the latest arrival and the last
headway matter most here. Route and direction are constant in this dataset,
so a zero score for them tells us little about their usefulness elsewhere.

### Code cell 13: check that the notebook matches the server experiment

Our coursework should explain the model the backend actually uses. This cell
reads the saved results from the admin-panel experiment and matches each
test query by its stage, service, session and time. It compares the waiting
times, predicted ranges and baseline predictions with this notebook's run.

A maximum absolute difference of zero means the compared values match
exactly. The assertions allow only tiny numerical differences and check the
training and testing dates too. Another fingerprint check confirms that the
source file was left unchanged.

The cell saves a verification summary. Matching results shows that the
notebook reproduces the saved experiment; it does not test the current live
API or establish how well the model works on future field data.


### Code cell 14: load the real Nairobi network

This dataset tells us where stops were mapped and which routes were linked to
them. Its 4,284 stop records and 136 routes come from the official historical
Digital Matatus feed. Some stop records represent station areas and others
represent individual boarding points, so the count is not a count of unique
station names.

The preparation script checks coordinates and connections between the tables.
Here we read its results, check the archive fingerprint, and count 85 stops
and 93 linked routes within our CBD study rectangle. The leading zeros in
stop IDs are preserved because they are identifiers, not quantities.

This supports NganyaFlow's stage browsing and route information. The feed does
not tell us when successive vehicles actually arrived on particular dates,
so it cannot supply the training answers used in our waiting-time test.

### Code cell 15: connect the network data to our study locations

We find the source records named Kencom/Ambassadeur, Odeon and Railways, then
look up routes linked to those boarding points. Similar names can refer to
different platforms; keeping their original IDs avoids merging them by mistake.

The graph plots longitude from left to right and latitude from bottom to top.
Each point is a mapped stop, and the three labelled points help locate our
study areas. It is a coordinate plot, not a live map or a display of vehicles.

The final table gives the two datasets separate jobs. The network helps us
describe stages and routes; the arrival sequences let us train and check wait
predictions. The model's earlier error scores still belong to the arrival
experiment. Current route availability needs checking because the network
release is historical.
