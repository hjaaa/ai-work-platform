import http from './http'

export function listGenerations(projectId) {
  return http.get(`/projects/${projectId}/generations`)
}

export function listDeployments(projectId) {
  return http.get(`/projects/${projectId}/deployments`)
}
