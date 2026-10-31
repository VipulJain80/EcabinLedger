export function initialReviewerSeverityId({hasSubmittedReport, snapshotSeverityId, defectSeverityName, severities}) {
  if (snapshotSeverityId) return snapshotSeverityId;
  if (hasSubmittedReport) return "";
  return severities.find(item => item.name === defectSeverityName)?.id || "";
}

export function reviewerSeverityChange(originalSeverityId, reviewerSeverityId, reason = "") {
  const changed = Boolean(reviewerSeverityId) && reviewerSeverityId !== originalSeverityId;
  return { changed, reasonRequired: changed, reasonValid: !changed || Boolean(reason.trim()) };
}
