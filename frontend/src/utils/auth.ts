/**
 * Token 管理工具
 */

const TOKEN_KEY = 'jwt_token'

/**
 * 获取 token
 */
export const getToken = (): string | null => {
  return localStorage.getItem(TOKEN_KEY)
}

/**
 * 保存 token
 */
export const setToken = (token: string): void => {
  localStorage.setItem(TOKEN_KEY, token)
}

/**
 * 清除 token
 */
export const removeToken = (): void => {
  localStorage.removeItem(TOKEN_KEY)
}

/**
 * 检查是否有 token
 */
export const hasToken = (): boolean => {
  return getToken() !== null
}

