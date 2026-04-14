import http from './http'

export function listSkills({ scope, projectId, includeSystem } = {}) {
  return http.get('/skills', { params: { scope, projectId, includeSystem } })
}

export function createSkill({ name, description, scope, projectId }) {
  return http.post('/skills', { name, description, scope, projectId })
}

export function updateSkill(skillId, { name, description }) {
  return http.put(`/skills/${skillId}`, { name, description })
}

export function deleteSkill(skillId) {
  return http.delete(`/skills/${skillId}`)
}
