import http from './http'

export function generatePrd(projectId, description) {
  return http.post(`/projects/${projectId}/prd/generate`, null, { params: { description } })
}

export function refinePrd(projectId, currentPrd, answer) {
  return http.post(`/projects/${projectId}/prd/refine`, null, { params: { currentPrd, answer } })
}

export function finalizePrd(projectId, currentPrd) {
  return http.post(`/projects/${projectId}/prd/finalize`, null, { params: { currentPrd } })
}

export function exportPrd(projectId, prdContent) {
  return http.post(`/projects/${projectId}/prd/export`, null, {
    params: { prdContent },
    responseType: 'blob'
  })
}

export function uploadDoc(projectId, file) {
  const formData = new FormData()
  formData.append('file', file)
  return http.post(`/projects/${projectId}/doc/upload`, formData, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}

export function fetchDoc(projectId, url) {
  return http.post(`/projects/${projectId}/doc/fetch`, null, { params: { url } })
}
