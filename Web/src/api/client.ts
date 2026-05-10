import axios from 'axios'

const TOKEN_KEY = 'lansync_token'
const SERVER_KEY = 'lansync_server'

export interface LoginRequest {
  username: string
  password: string
}

export interface LoginResponse {
  token: string
  username: string
}

export interface GalleryPhoto {
  id: string
  name: string
  path: string
  size: number
  type: 'image' | 'video'
  hasLocal?: boolean
  localUri?: string | null
  thumbnailUrl?: string
}

export interface GalleryGroup {
  date: string
  photos: GalleryPhoto[]
}

export interface DeletePhotosRequest {
  paths: string[]
}

export interface DeletePhotosResponse {
  deleted: string[]
  failed: { path: string; error: string }[]
}

class ApiClient {
  private baseUrl: string = ''
  private token: string = ''

  constructor() {
    this.baseUrl = localStorage.getItem(SERVER_KEY) || ''
    this.token = localStorage.getItem(TOKEN_KEY) || ''
  }

  setBaseUrl(url: string) {
    this.baseUrl = url.endsWith('/') ? url : url + '/'
    localStorage.setItem(SERVER_KEY, this.baseUrl)
  }

  getBaseUrl() {
    return this.baseUrl
  }

  setToken(token: string) {
    this.token = token
    localStorage.setItem(TOKEN_KEY, token)
  }

  getToken() {
    return this.token
  }

  clearAuth() {
    this.token = ''
    localStorage.removeItem(TOKEN_KEY)
  }

  isLoggedIn() {
    return !!this.token && !!this.baseUrl
  }

  private get client() {
    return axios.create({
      baseURL: this.baseUrl,
      headers: {
        'Authorization': `Bearer ${this.token}`,
        'Content-Type': 'application/json',
      },
      timeout: 30000,
    })
  }

  async login(username: string, password: string, serverUrl: string): Promise<LoginResponse> {
    this.setBaseUrl(serverUrl)
    const resp = await this.client.post<LoginResponse>('api/login', { username, password })
    this.setToken(resp.data.token)
    return resp.data
  }

  async register(username: string, password: string): Promise<void> {
    await this.client.post('api/register', { username, password })
  }

  async getGallery(): Promise<GalleryGroup[]> {
    const resp = await this.client.get<{ success: boolean; groups: GalleryGroup[] }>('api/gallery/list')
    return resp.data.groups
  }

  async deletePhotos(paths: string[]): Promise<DeletePhotosResponse> {
    const resp = await this.client.post<DeletePhotosResponse>('api/gallery/delete', { paths })
    return resp.data
  }

  async getPhotoUrl(path: string): Promise<string> {
    return `${this.baseUrl}api/gallery/photo/${path}?token=${this.token}`
  }

  async downloadPhoto(path: string): Promise<Blob> {
    const resp = await axios.get(`${this.baseUrl}api/gallery/photo/${path}`, {
      headers: { 'Authorization': `Bearer ${this.token}` },
      responseType: 'blob',
    })
    return resp.data
  }

  async renamePhoto(oldPath: string, newName: string): Promise<void> {
    await this.client.post('api/gallery/rename', { path: oldPath, new_name: newName })
  }

  async uploadPhoto(file: File, onProgress?: (pct: number) => void): Promise<void> {
    const formData = new FormData()
    formData.append('file', file)
    await axios.post(`${this.baseUrl}api/upload`, formData, {
      headers: {
        'Authorization': `Bearer ${this.token}`,
        'Content-Type': 'multipart/form-data',
      },
      onUploadProgress: (e) => {
        if (e.total && onProgress) {
          onProgress(Math.round((e.loaded * 100) / e.total))
        }
      },
    })
  }
}

export const api = new ApiClient()
