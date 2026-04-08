import { Client } from '@stomp/stompjs'

let stompClient = null
let threadSubscription = null

/**
 * 建立 WebSocket 连接（项目级别）
 * 连接建立后通过 subscribeThread 订阅具体线程
 */
export function connectWebSocket(onConnect) {
  disconnectWebSocket()

  const wsUrl = `${window.location.protocol === 'https:' ? 'wss:' : 'ws:'}//${window.location.host}/ws`

  stompClient = new Client({
    brokerURL: wsUrl,
    reconnectDelay: 5000,
    heartbeatIncoming: 10000,
    heartbeatOutgoing: 10000,
    onConnect: () => {
      console.log('WebSocket 已连接')
      if (onConnect) onConnect()
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

/**
 * 订阅指定线程的消息
 * 切换线程时先调用 unsubscribeThread 再调用此方法
 */
export function subscribeThread(threadId, onMessage) {
  if (!stompClient || !stompClient.connected) {
    console.warn('WebSocket 未连接，无法订阅线程:', threadId)
    return
  }

  unsubscribeThread()

  threadSubscription = stompClient.subscribe(`/topic/thread/${threadId}`, (message) => {
    try {
      const body = JSON.parse(message.body)
      onMessage(body)
    } catch (e) {
      console.error('消息解析失败:', e)
    }
  })
}

/**
 * 取消当前线程订阅
 */
export function unsubscribeThread() {
  if (threadSubscription) {
    threadSubscription.unsubscribe()
    threadSubscription = null
  }
}

/**
 * 断开 WebSocket 连接
 */
export function disconnectWebSocket() {
  unsubscribeThread()
  if (stompClient) {
    stompClient.deactivate()
    stompClient = null
  }
}

/**
 * 检查 WebSocket 是否已连接
 */
export function isConnected() {
  return stompClient && stompClient.connected
}
