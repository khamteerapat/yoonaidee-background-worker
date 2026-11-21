# Yoo Nai Dee Background Worker

When a user shares their workplace, rental budget, and preferred transport in LINE, the app saves an analysis job in PostgreSQL. The **background worker** researches Bangkok neighborhoods and sends the result back through LINE, so the initial message does not have to wait for the research to finish.

## Workflow

```text
+---------------------------------------------+
| User chats in LINE                          |
+---------------------------------------------+
                      |
                      v
+---------------------------------------------+
| App saves analysis job in PostgreSQL        |
+---------------------------------------------+
                      |
                      v
+---------------------------------------------+
| Worker claims the job                       |
+---------------------------------------------+
                      |
                      v
+---------------------------------------------+
| AI researches Bangkok neighborhoods online |
+---------------------------------------------+
                      |
                      v
+---------------------------------------------+
| Worker validates and saves 3 neighborhoods  |
+---------------------------------------------+
                      |
                      v
+---------------------------------------------+
| Notification queue                          |
+---------------------------------------------+
                      |
                      v
+---------------------------------------------+
| Worker sends LINE Flex result to the user   |
+---------------------------------------------+
```

1. **Claim:** The worker takes a ready analysis job from PostgreSQL, along with the user's saved preferences.
2. **Research:** The AI uses web search to suggest three neighborhoods and explain their benefits, trade-offs, and sources.
3. **Validate and save:** The worker checks that the three neighborhoods are distinct, the text fits the required limits, and source links came from the search results. It then saves the result.
4. **Deliver:** A separate notification poller sends a LINE Flex message with the neighborhood names, reasons, caveats, and available source links.
