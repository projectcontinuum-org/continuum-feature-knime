# KNIME Workflow Executor Node

Seamlessly execute KNIME analytics workflows as part of your Continuum automation. Download workflows from shared locations, run them end-to-end, and automatically upload results for downstream processing or archival.

---

## What It Does

Use this node when you need to run KNIME workflows as part of a larger automated process. Simply configure where your workflow is stored — the node handles everything else:

1. **Retrieves** your KNIME workflow from a shared location
2. **Executes** the workflow end-to-end
3. **Uploads** completed results to the worker's configured location
4. **Tracks** progress and reports any issues

**What You Get:**
- ✅ Standalone workflow execution (no input data tables needed)
- ✅ Automatic progress tracking and status updates
- ✅ Execution timeout protection (prevents hanging jobs)
- ✅ Detailed result archival and auditability
- ✅ Clean error reporting when something goes wrong

---

## Configuration

### Input Ports
| Port | Type | Format | Description |
|------|------|--------|-------------|
| (none) | - | - | This is a source/execution node - no input tables required |

### Output Ports
| Port | Type | Format | Description |
|------|------|--------|-------------|
| (none) | - | - | Results are automatically archived by the worker |

### Properties

| Setting | Required | What It Does |
|---------|----------|--------------|
| **Workflow Location** | Yes | Where your KNIME workflow file is stored (local path, web URL, or cloud storage) |
| **Execution Timeout** | No | Maximum time to wait for workflow to complete (default: 5 minutes) |

**Note:** Results are automatically stored in the worker's configured results location. Temporary files are automatically cleaned up after upload to conserve disk space.

**Common Configuration:**
```json
{
  "workflowLocation": "https://company.com/workflows/sales-report.knwf",
  "timeoutSeconds": 600
}
```

---

## How to Use

### Step 1: Prepare Your Workflow

Export your KNIME workflow as a `.knwf` file or `.zip` archive and save it in a location accessible from Continuum (local network, web server, or cloud storage).

### Step 2: Configure the Node

Add the KNIME Workflow Executor node to your Continuum workflow and fill in:

- **Workflow Location** - Where your KNIME workflow is stored
- **Timeout** - How long to wait (usually 5-10 minutes is fine, use longer for complex workflows)

Results will be automatically saved to the worker's configured location — no need to specify an output path.

### Step 3: Run and Monitor

Execute your workflow and watch progress in real-time. Once complete, results are automatically archived.

---

## Supported Locations

You can store workflows and results in several places:

| Location Type | Example | Notes |
|---------------|---------|-------|
| Local Network | `/shared/workflows/my-analysis.knwf` | File must be accessible from the worker |
| Web Server | `https://company.com/workflows/report.zip` | Use HTTPS for secure download |
| Cloud Storage | `s3://my-bucket/workflows/` | (Future support planned) |

---

## If Something Goes Wrong

If the node encounters an issue, it will report the error clearly. Here are the most common problems and solutions:

| Problem | What to Check |
|---------|---------------|
| "Workflow location not found" | Verify the workflow file path or URL is correct and accessible |
| "Workflow timed out" | The workflow took too long. Increase the timeout setting or simplify the workflow |
| "Workflow failed with error" | Check the workflow in KNIME directly to verify it runs successfully |
| "No space left on device" | The server ran out of disk space. Contact your administrator |

For detailed diagnostics, you can save the temporary files instead of auto-deleting them so your team can inspect intermediate results.



---

## When to Use This Node

### Automated Reporting
Run KNIME analytics workflows on a schedule to generate reports automatically. Results are saved for distribution to stakeholders.

### Data Pipeline Integration
Include KNIME analysis as part of a larger data processing pipeline. Load data, run KNIME analysis, and feed results to the next step.

### Batch Analytics
Execute complex data analysis on large datasets regularly. Let KNIME handle the heavy computation while Continuum orchestrates the workflow.

### Analytics-as-a-Service
Provide data analysis capabilities to other teams by embedding KNIME workflows in Continuum workflows they can trigger on-demand.

---

## Example Scenarios

### Scenario 1: Daily Sales Report
**Goal:** Generate daily sales analysis every morning

**Setup:**
- Store a KNIME workflow that loads daily sales data and creates visualizations
- Configure node to run on schedule
- Results automatically saved for email distribution

**Configuration:**
```json
{
  "workflowLocation": "/shared/workflows/daily-sales.knwf",
  "timeoutSeconds": 300
}
```

---

### Scenario 2: Weekly Forecast Model
**Goal:** Update forecasts every week using latest data

**Setup:**
- KNIME workflow loads historical data and runs ML forecast model
- Continuum runs it every Monday morning
- Results uploaded to dashboard database

**Configuration:**
```json
{
  "workflowLocation": "https://analytics.company.com/workflows/forecast-model.zip",
  "timeoutSeconds": 1200
}
```

---

### Scenario 3: Data Quality Checks
**Goal:** Run automated data quality checks on incoming files

**Setup:**
- KNIME workflow validates new data against quality rules
- Continuum triggers check when new files arrive
- Pass/fail results route data to appropriate folder

**Configuration:**
```json
{
  "workflowLocation": "/shared/workflows/quality-check.knwf",
  "timeoutSeconds": 600
}
```

---

## Tips for Success

### Choosing a Timeout
- **Simple workflows** (5-10 min): 300 seconds (5 minutes)
- **Medium workflows** (15-30 min): 600-900 seconds (10-15 minutes)  
- **Complex workflows** (30+ min): 1200-1800 seconds (20-30 minutes)

Set it higher than you think you'll need — unused time doesn't cost anything.

### Disk Space Management
Temporary files are automatically cleaned up after workflows complete and results are uploaded. This ensures efficient disk utilization on worker nodes. The worker can safely execute multiple workflows in sequence without manual intervention.

### Testing New Workflows
Before automating, test your KNIME workflow manually to verify it works correctly. The node will execute it exactly as-is, so any issues in the workflow will appear during execution.

### Debugging Issues
If a workflow fails, execution logs are automatically archived with the results before cleanup. Contact your administrator to review logs and diagnostics if needed.

---

## Performance Considerations

| Factor | Impact |
|--------|--------|
| Workflow complexity | Longer workflows need higher timeout values |
| Network speed | Large workflows take longer to download/upload |
| Result size | Large result files take longer to upload |
| Server load | Multiple workflows running simultaneously share resources |

Most workflows complete in seconds to minutes. If consistently timing out, the workflow may need optimization in KNIME.

---

---

## Need Help?

If your workflow isn't working as expected:

1. **Test manually first** - Run the workflow in KNIME desktop to ensure it works
2. **Check file paths** - Verify workflow location is accessible
3. **Review timeout** - Increase timeout if workflow is complex or handles large data
4. **Contact support** - Provide workflow name, configuration, and error message

## Next Steps

Once you've set up your workflow, consider combining this node with other Continuum features:
- **Scheduling** - Run automatically on a schedule
- **Notifications** - Alert teams when results are ready
- **Data Integration** - Feed results to downstream systems
- **Monitoring** - Track workflow success rates and performance
