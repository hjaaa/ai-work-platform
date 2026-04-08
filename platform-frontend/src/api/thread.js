import http from './http'

export function createThread(projectId) {
  return http.post(`/projects/${projectId}/threads`)
}

export function listThreads(projectId) {
  return http.get(`/projects/${projectId}/threads`)
}

export function updateThread(threadId, title) {
  return http.put(`/threads/${threadId}`, { title })
}

export function deleteThread(threadId) {
  return http.delete(`/threads/${threadId}`)
}

export function getThreadMessages(threadId) {
  return http.get(`/threads/${threadId}/messages`)
}

export function sendThreadMessage(threadId, message, filePaths) {
  const params = { message }
  if (filePaths && filePaths.length > 0) {
    params.filePaths = filePaths
  }
  return http.post(`/threads/${threadId}/chat`, null, { params })
}

export function uploadThreadFile(threadId, file) {
  const formData = new FormData()
  formData.append('file', file)
  return http.post(`/threads/${threadId}/upload`, formData, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}

export function cancelThread(threadId) {
  return http.post(`/threads/${threadId}/cancel`)
}
