import http from './http'

export function getFileTree(projectId) {
  return http.get(`/projects/${projectId}/files/tree`)
}

export function getFileContent(projectId, path) {
  return http.get(`/projects/${projectId}/files/content`, { params: { path } })
}
