import { Client } from '@stomp/stompjs'

let stompClient = null

export function connectWebSocket(projectId, onMessage) {
  // 先断开旧连接，防止切换项目时泄漏
  disconnectWebSocket()

  const wsUrl = `${window.location.protocol === 'https:' ? 'wss:' : 'ws:'}//${window.location.host}/ws`

  stompClient = new Client({
    brokerURL: wsUrl,
    reconnectDelay: 5000,
    heartbeatIncoming: 10000,
    heartbeatOutgoing: 10000,
    onConnect: () => {
      console.log('WebSocket 已连接')
      stompClient.subscribe(`/topic/project/${projectId}`, (message) => {
        try {
          const body = JSON.parse(message.body)
          onMessage(body)
        } catch (e) {
          console.error('消息解析失败:', e)
        }
      })
    },
    onStompError: (frame) => {
      console.error('WebSocket STOMP 错误:', frame.headers['message'])
    },
    onWebSocketClose: () => {
      console.warn('WebSocket 连接断开，将在 5 秒后重连')
    },
    onDisconnect: () => {
      console.log('WebSocket 已断开')
    }
  })

  stompClient.activate()
  return stompClient
}

export function disconnectWebSocket() {
  if (stompClient) {
    stompClient.deactivate()
    stompClient = null
  }
}
