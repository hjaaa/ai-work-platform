import http from './http'

export function triggerDeploy(projectId, serverName) {
  return http.post(`/projects/${projectId}/deploy`, null, { params: { serverName } })
}

export function listServers() {
  return http.get('/servers')
}
