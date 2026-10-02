export async function cancelPendingReview(pendingReview, deleteFile, closeReview) {
  if (pendingReview?.fileId) await deleteFile(pendingReview.fileId)
  closeReview()
}
