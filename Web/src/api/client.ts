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

  getThumbnailUrl(path: string): string {
    // Direct URL — browser requests it via <img loading="lazy"> when element enters viewport
    // No JS-side fetching = no concurrent request burst
    const encoded = encodeURIComponent(path)
    return `${this.baseUrl}api/gallery/thumb/${encoded}?token=${this.token}`
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
    // Try to read EXIF DateTime (tag 306) from JPEG before uploading
    // This captures the actual photo-taking date even when iOS/Safari re-encodes the image
    let timestamp: number | null = null
    if (file.type === 'image/jpeg' || file.name.toLowerCase().match(/\.(jpg|jpeg)$/)) {
      try {
        const buffer = await file.slice(0, 131072).arrayBuffer() // read first 128KB (enough for EXIF header)
        timestamp = await readExifDateTime(buffer)
      } catch { /* ignore EXIF parse errors */ }
    }

    const formData = new FormData()
    formData.append('file', file)
    if (timestamp) {
      formData.append('timestamp', Math.floor(timestamp).toString())
    }

    // W-3: Add error handling and retry logic for upload
    const maxRetries = 3
    let lastError: Error | null = null

    for (let attempt = 1; attempt <= maxRetries; attempt++) {
      try {
        await axios.post(`${this.baseUrl}api/upload/photo`, formData, {
          headers: {
            'Authorization': `Bearer ${this.token}`,
          },
          onUploadProgress: (e) => {
            if (e.total && onProgress) {
              onProgress(Math.round((e.loaded * 100) / e.total))
            }
          },
        })
        return // Success, exit the retry loop
      } catch (error: any) {
        lastError = error

        // Check if it's a 401 error (token expired/invalid)
        if (error.response?.status === 401) {
          // Clear auth and throw error indicating re-login is needed
          this.clearAuth()
          throw new Error('AUTH_EXPIRED: Token expired, please re-login')
        }

        // Check if it's a network error (no response from server)
        if (!error.response) {
          // Network error - retry if we have attempts left
          if (attempt < maxRetries) {
            // Wait before retrying with exponential backoff
            await new Promise(resolve => setTimeout(resolve, 1000 * attempt))
            continue
          }
        }

        // For other errors (4xx client errors, 5xx server errors), don't retry
        if (error.response?.status >= 400 && error.response?.status < 500) {
          throw error
        }

        // For server errors (5xx) or network errors on last attempt, throw
        if (attempt >= maxRetries) {
          throw error
        }
      }
    }

    // This should never be reached, but just in case
    if (lastError) {
      throw lastError
    }
  }
}

/** Parse EXIF tag 306 (DateTime) from JPEG binary ArrayBuffer, returns Unix timestamp or null */
async function readExifDateTime(buffer: ArrayBuffer): Promise<number | null> {
  const bytes = new Uint8Array(buffer)
  // Find APP1 marker (0xFF 0xE1)
  for (let i = 0; i < bytes.length - 4; i++) {
    if (bytes[i] === 0xFF && bytes[i+1] === 0xE1) {
      const len = (bytes[i+2] << 8) | bytes[i+3]
      const app1Content = bytes.slice(i+4, i+2+len)
      const marker = String.fromCharCode(...app1Content.slice(0, 4))
      if (marker !== 'Exif') break
      // Skip byte order mark (II or MM) + magic (2a 00)
      const tiffStart = 6
      const byteOrder = String.fromCharCode(app1Content[tiffStart], app1Content[tiffStart+1])
      const isLittle = byteOrder === 'II'
      const readU16 = (offset: number) => {
        const b0 = app1Content[tiffStart + offset], b1 = app1Content[tiffStart + offset + 1]
        return isLittle ? (b0 | (b1 << 8)) : ((b0 << 8) | b1)
      }
      const readU32 = (offset: number) => {
        const b0 = app1Content[tiffStart + offset], b1 = app1Content[tiffStart + offset + 1]
        const b2 = app1Content[tiffStart + offset + 2], b3 = app1Content[tiffStart + offset + 3]
        return isLittle ? (b0 | (b1<<8) | (b2<<16) | (b3<<24)) : ((b0<<24) | (b1<<16) | (b2<<8) | b3)
      }
      const ifdOffset = readU32(4)
      const numEntries = readU16(ifdOffset)
      for (let j = 0; j < numEntries; j++) {
        const entryOffset = ifdOffset + 2 + j * 12
        const tag = readU16(entryOffset)
        if (tag === 306) { // DateTime tag
          const type = readU16(entryOffset + 2)
          if (type === 2) { // ASCII string
            const count = readU32(entryOffset + 4)
            const valueOffset = count > 4 ? readU32(entryOffset + 8) : entryOffset + 8
            const chars: string[] = []
            for (let k = 0; k < count - 1; k++) {
              const ch = app1Content[tiffStart + valueOffset + k]
              if (ch === 0) break
              chars.push(String.fromCharCode(ch))
            }
            const dtStr = chars.join('')
            // Format: "YYYY:MM:DD HH:MM:SS"
            const m = dtStr.match(/^(\d{4}):(\d{2}):(\d{2}) (\d{2}):(\d{2}):(\d{2})/)
            if (m) {
              return new Date(+m[1], +m[2]-1, +m[3], +m[4], +m[5], +m[6]).getTime() / 1000
            }
          }
        }
      }
      break
    }
  }
  return null
}

export const api = new ApiClient()
