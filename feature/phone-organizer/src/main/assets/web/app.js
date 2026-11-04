(() => {
  'use strict'

  const $ = (selector) => document.querySelector(selector)
  const state = {
    session: null,
    root: null,
    path: '',
    roots: [],
    files: [],
    hidden: false,
    sort: 'name',
    selected: new Set(),
    view: 'files',
    pickerPath: '',
    transfers: [],
  }

  const ui = {
    login: $('#login'),
    loginCode: $('#loginCode'),
    loginWait: $('#loginWait'),
    loginMsg: $('#loginMsg'),
    code: $('#code'),
    connect: $('#connect'),
    shell: $('#shell'),
    tvName: $('#tvName'),
    nav: document.querySelectorAll('.nav button'),
    views: {
      files: $('#view-files'),
      push: $('#view-push'),
      desktop: $('#view-desktop'),
      more: $('#view-more'),
    },
    roots: $('#roots'),
    crumb: $('#crumb'),
    fileList: $('#fileList'),
    selection: $('#selection'),
    selectionText: $('#selectionText'),
    uploadQueue: $('#uploadQueue'),
    fileInput: $('#fileInput'),
    folderInput: $('#folderInput'),
    pushInput: $('#pushInput'),
    pushList: $('#pushList'),
    appList: $('#appList'),
    wallpaperInput: $('#wallpaperInput'),
    clipInput: $('#clipInput'),
    city: $('#city'),
    grant: $('#grant'),
    dialog: $('#dialog'),
    dialogTitle: $('#dialogTitle'),
    dialogBody: $('#dialogBody'),
    dialogActions: $('#dialogActions'),
    toast: $('#toast'),
  }

  const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

  function toast(text) {
    ui.toast.textContent = text
    ui.toast.hidden = false
    clearTimeout(toast.timer)
    toast.timer = setTimeout(() => { ui.toast.hidden = true }, 2800)
  }

  function fmtSize(bytes) {
    const n = Number(bytes) || 0
    if (n < 1024) return `${n} B`
    const units = ['KB', 'MB', 'GB', 'TB']
    let value = n
    let index = -1
    while (value >= 1024 && index < units.length - 1) {
      value /= 1024
      index++
    }
    return `${value.toFixed(1)} ${units[index]}`
  }

  function fmtTime(ms) {
    if (!ms) return ''
    const d = new Date(ms)
    const p = (n) => String(n).padStart(2, '0')
    return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
  }

  function deviceLabel() {
    const ua = navigator.userAgent || ''
    const platform = /iPhone/.test(ua) ? 'iPhone' : /iPad/.test(ua) ? 'iPad' :
      /Android/.test(ua) ? 'Android' : /Windows/.test(ua) ? 'Windows' :
      /Macintosh/.test(ua) ? 'Mac' : 'Browser'
    const browser = /Edg\//.test(ua) ? 'Edge' : /Chrome\//.test(ua) ? 'Chrome' :
      /Safari\//.test(ua) ? 'Safari' : /Firefox\//.test(ua) ? 'Firefox' : 'Browser'
    return `${platform} · ${browser}`
  }

  async function api(method, path, body, headers) {
    const h = Object.assign({}, headers || {})
    if (state.session) h['X-Session'] = state.session
    const response = await fetch(path, { method, headers: h, body })
    if (response.status === 401 && path !== '/api/session') {
      logout('连接已断开')
      throw new Error('unauthorized')
    }
    return response
  }

  async function jsonApi(method, path, value) {
    const headers = value === undefined ? undefined : { 'Content-Type': 'application/json' }
    const body = value === undefined ? undefined : JSON.stringify(value)
    const response = await api(method, path, body, headers)
    let data = null
    try { data = await response.json() } catch (_) { data = null }
    return { status: response.status, data }
  }

  function logout(message) {
    state.session = null
    state.selected.clear()
    ui.shell.hidden = true
    ui.login.hidden = false
    ui.loginWait.hidden = true
    ui.loginCode.hidden = false
    ui.loginMsg.textContent = message || ''
    stopTransfersPoll()
  }

  async function login(credential) {
    ui.loginCode.hidden = true
    ui.loginWait.hidden = false
    ui.loginMsg.textContent = ''
    const headers = { 'X-Device': deviceLabel() }
    if (credential.token) headers['X-Token'] = credential.token
    else headers['X-Code'] = credential.code
    try {
      const response = await fetch('/api/session', { method: 'POST', headers })
      if (response.ok) {
        const data = await response.json()
        state.session = data.session
        history.replaceState(null, '', '/')
        ui.login.hidden = true
        ui.shell.hidden = false
        await enterApp()
        return
      }
      if (response.status === 403 || response.status === 408) {
        ui.loginMsg.textContent = '电视拒绝了连接'
      } else {
        ui.loginMsg.textContent = '配对码已失效'
      }
    } catch (_) {
      ui.loginMsg.textContent = '连接已断开'
    }
    ui.loginWait.hidden = true
    ui.loginCode.hidden = false
  }

  async function enterApp() {
    try {
      const desktop = await jsonApi('GET', '/api/desktop')
      ui.tvName.textContent = (desktop.data && desktop.data.device) || ''
      ui.city.value = (desktop.data && desktop.data.weatherCity) || ''
    } catch (_) {}
    await loadRoots()
    await refreshTransfers()
    startTransfersPoll()
    setView('files')
  }

  function setView(view) {
    state.view = view
    for (const button of ui.nav) button.classList.toggle('on', button.dataset.view === view)
    for (const [name, element] of Object.entries(ui.views)) element.hidden = name !== view
    if (view === 'push') refreshTransfers()
    if (view === 'desktop') loadDesktop()
    if (view === 'more') loadMore()
  }

  // ------------------------------------------------------------------ files
  async function loadRoots() {
    const result = await jsonApi('GET', '/api/fs/roots')
    state.roots = Array.isArray(result.data) ? result.data : []
    if (!state.root && state.roots.length) state.root = state.roots[0].id
    const target = state.roots.find((root) => root.id === state.root)
    renderRoots()
    if (target) await listDir(state.root, state.path)
    else renderEmpty('空文件夹')
  }

  function renderRoots() {
    ui.roots.textContent = ''
    for (const root of state.roots) {
      const chip = document.createElement('button')
      chip.className = 'chip' + (root.id === state.root ? ' on' : '')
      chip.innerHTML = `<svg class="ico"><use href="/icons.svg#folder"></use></svg>`
      const label = document.createElement('span')
      label.textContent = root.label
      const free = document.createElement('small')
      free.textContent = `${fmtSize(root.free)}`
      chip.append(label, free)
      chip.onclick = () => {
        state.root = root.id
        state.path = ''
        state.selected.clear()
        renderRoots()
        listDir(state.root, state.path)
      }
      ui.roots.append(chip)
    }
  }

  async function listDir(root, path) {
    state.root = root
    state.path = path || ''
    state.selected.clear()
    const query = new URLSearchParams({ root, path: state.path, hidden: state.hidden ? '1' : '0' })
    const response = await api('GET', `/api/fs/list?${query}`)
    if (response.status !== 200) {
      toast(response.status === 404 ? '找不到这个位置' : '失败')
      renderEmpty('空文件夹')
      return
    }
    const data = await response.json()
    state.files = data.entries || []
    renderCrumb()
    renderFiles()
    renderSelection()
  }

  function renderCrumb() {
    ui.crumb.textContent = ''
    const root = state.roots.find((item) => item.id === state.root)
    const parts = state.path ? state.path.split('/') : []
    const rootButton = document.createElement('button')
    rootButton.className = 'chip'
    rootButton.textContent = root ? root.label : state.root || ''
    rootButton.onclick = () => listDir(state.root, '')
    ui.crumb.append(rootButton)
    let prefix = ''
    for (const part of parts) {
      prefix = prefix ? `${prefix}/${part}` : part
      const separator = document.createElement('span')
      separator.textContent = '/'
      const button = document.createElement('button')
      button.className = 'chip'
      button.textContent = part
      const target = prefix
      button.onclick = () => listDir(state.root, target)
      ui.crumb.append(separator, button)
    }
  }

  function sortedFiles() {
    const files = state.files.slice()
    if (state.sort === 'size') files.sort((a, b) => (a.dir === b.dir ? (b.size - a.size) : (a.dir ? -1 : 1)))
    else if (state.sort === 'time') files.sort((a, b) => (a.dir === b.dir ? (b.mtime - a.mtime) : (a.dir ? -1 : 1)))
    else files.sort((a, b) => (a.dir === b.dir ? a.name.localeCompare(b.name) : (a.dir ? -1 : 1)))
    return files
  }

  function renderFiles() {
    ui.fileList.textContent = ''
    const files = sortedFiles()
    if (!files.length) {
      renderEmpty('空文件夹')
      return
    }
    for (const file of files) {
      const row = document.createElement('div')
      row.className = 'row' + (state.selected.has(file.name) ? ' sel' : '')
      const pick = document.createElement('label')
      pick.className = 'pick'
      const check = document.createElement('input')
      check.type = 'checkbox'
      check.checked = state.selected.has(file.name)
      check.onchange = () => {
        if (check.checked) state.selected.add(file.name)
        else state.selected.delete(file.name)
        row.classList.toggle('sel', check.checked)
        renderSelection()
      }
      pick.append(check)
      const thumb = document.createElement('div')
      thumb.className = 'thumb' + (file.dir ? ' dir' : '')
      thumb.innerHTML = `<svg class="ico"><use href="/icons.svg#${iconFor(file)}"></use></svg>`
      const name = document.createElement('div')
      name.className = 'name'
      const title = document.createElement('b')
      title.textContent = file.name
      const meta = document.createElement('span')
      meta.textContent = file.dir ? '' : `${fmtSize(file.size)} · ${fmtTime(file.mtime)}`
      name.append(title, meta)
      const actions = document.createElement('div')
      actions.className = 'actions'
      const more = document.createElement('button')
      more.innerHTML = `<svg class="ico"><use href="/icons.svg#more"></use></svg>`
      more.onclick = (event) => {
        event.stopPropagation()
        showFileActions(file)
      }
      actions.append(more)
      row.append(pick, thumb, name, actions)
      row.onclick = () => {
        if (file.dir) listDir(state.root, state.path ? `${state.path}/${file.name}` : file.name)
      }
      ui.fileList.append(row)
    }
  }

  function iconFor(file) {
    if (file.dir) return 'folder'
    const mime = file.mime || ''
    if (mime.startsWith('image/')) return 'image'
    if (mime.startsWith('video/') || mime.startsWith('audio/')) return 'play'
    return 'file'
  }

  function renderEmpty(text) {
    ui.fileList.innerHTML = `<div class="empty"><svg class="ico"><use href="/icons.svg#folder"></use></svg><span>${text}</span></div>`
  }

  function renderSelection() {
    const count = state.selected.size
    ui.selection.hidden = count === 0
    if (count) ui.selectionText.textContent = `已选 ${count} 项`
  }

  function showDialog(title, body, actions) {
    ui.dialogTitle.textContent = title
    ui.dialogBody.textContent = ''
    if (typeof body === 'string') {
      const p = document.createElement('div')
      p.textContent = body
      ui.dialogBody.append(p)
    } else {
      ui.dialogBody.append(body)
    }
    ui.dialogActions.textContent = ''
    for (const action of actions) {
      const button = document.createElement('button')
      button.className = action.className || 'ghost'
      button.textContent = action.label
      button.onclick = async () => {
        if (action.keepOpen) {
          await action.run()
        } else {
          ui.dialog.hidden = true
          await action.run()
        }
      }
      ui.dialogActions.append(button)
    }
    ui.dialog.hidden = false
  }

  function closeDialog() { ui.dialog.hidden = true }

  function input(value) {
    const element = document.createElement('input')
    element.type = 'text'
    element.value = value || ''
    return element
  }

  async function showFileActions(file) {
    const path = state.path ? `${state.path}/${file.name}` : file.name
    const actions = [
      { label: file.dir ? '下载' : '下载', className: 'primary', run: () => download(path, file.dir) },
    ]
    if (!file.dir) actions.push({ label: '在电视上打开', className: 'ghost', run: () => openOnTv(path) })
    actions.push({ label: '重命名', className: 'ghost', run: () => renameFile(path, file.name) })
    actions.push({ label: '移动到…', className: 'ghost', run: () => moveFile([path]) })
    actions.push({ label: '删除', className: 'danger', run: () => deletePaths([path]) })
    actions.push({ label: '取消', className: 'ghost', run: async () => {} })
    showDialog(file.name, '', actions)
  }

  async function download(path, isDir) {
    const result = await jsonApi('POST', '/api/fs/ticket', { root: state.root, paths: [path] })
    if (result.status !== 200 || !result.data) {
      toast('找不到这个位置')
      return
    }
    const link = document.createElement('a')
    link.href = result.data.url
    link.download = ''
    document.body.append(link)
    link.click()
    link.remove()
  }

  async function openOnTv(path) {
    const result = await jsonApi('POST', '/api/open', { root: state.root, path })
    if (result.status !== 200) toast(result.status === 415 ? '电视无法打开这类文件' : '失败')
  }

  function renameFile(path, current) {
    const field = input(current)
    showDialog('重命名', field, [
      { label: '取消', className: 'ghost', run: async () => {} },
      {
        label: '保存',
        className: 'primary',
        run: async () => {
          const result = await jsonApi('POST', '/api/fs/rename', {
            root: state.root,
            path,
            name: field.value.trim(),
          })
          if (result.status === 409) toast('已有 1 个同名文件')
          else if (result.status !== 200) toast('失败')
          else await listDir(state.root, state.path)
        },
      },
    ])
  }

  function moveFile(paths) {
    const field = input(state.path)
    showDialog('移动到…', field, [
      { label: '取消', className: 'ghost', run: async () => {} },
      {
        label: '移动到这里',
        className: 'primary',
        run: async () => {
          const result = await jsonApi('POST', '/api/fs/move', {
            root: state.root,
            paths,
            to: field.value.trim(),
          })
          if (result.status !== 200) toast('失败')
          else await listDir(state.root, state.path)
        },
      },
    ])
  }

  function deletePaths(paths) {
    showDialog(`删除 ${paths.length} 项？`, '', [
      { label: '取消', className: 'ghost', run: async () => {} },
      {
        label: '删除',
        className: 'danger',
        run: async () => {
          const result = await jsonApi('POST', '/api/fs/delete', { root: state.root, paths })
          if (result.status !== 200) toast('失败')
          else {
            toast('已删除')
            await listDir(state.root, state.path)
          }
        },
      },
    ])
  }

  function newFolder() {
    const field = input('')
    showDialog('新建文件夹', field, [
      { label: '取消', className: 'ghost', run: async () => {} },
      {
        label: '保存',
        className: 'primary',
        run: async () => {
          const result = await jsonApi('POST', '/api/fs/mkdir', {
            root: state.root,
            path: state.path,
            name: field.value.trim(),
          })
          if (result.status === 409) toast('已有 1 个同名文件')
          else if (result.status !== 200) toast('失败')
          else await listDir(state.root, state.path)
        },
      },
    ])
  }

  // ------------------------------------------------------------------ upload
  function addQueue(name, total) {
    const item = document.createElement('div')
    item.className = 'item'
    item.innerHTML = `<div class="line"><b></b><span>0%</span></div><div class="progress"><i></i></div>`
    item.querySelector('b').textContent = name
    ui.uploadQueue.append(item)
    return item
  }

  function updateQueue(item, done, total) {
    const percent = total > 0 ? Math.min(100, Math.round((done / total) * 100)) : 100
    item.querySelector('.line span').textContent = `${percent}%`
    item.querySelector('.progress i').style.width = `${percent}%`
  }

  function finishQueue(item, text) {
    const line = item.querySelector('.line span')
    line.textContent = text
    setTimeout(() => item.remove(), 2500)
  }

  async function uploadOne(file, options) {
    const meta = options.target === 'push'
      ? { target: 'push', name: file.name, size: file.size, keep: !!options.keep }
      : {
          root: options.root,
          path: options.path || '',
          name: file.name,
          size: file.size,
          mtime: file.lastModified || 0,
          conflict: options.conflict || 'rename',
        }
    let created
    try {
      created = await jsonApi('POST', '/api/fs/uploads', meta)
    } catch (error) {
      if (error.message === 'unauthorized') return false
      toast('连接已断开')
      return false
    }
    if (created.status === 409 && !options.conflict) {
      const choice = await conflictDialog()
      if (!choice) return false
      return uploadOne(file, Object.assign({}, options, { conflict: choice }))
    }
    if (created.status !== 201) {
      toast(created.status === 415 ? '电视无法打开这类文件' :
        created.status === 403 ? '这个位置不能写入' :
        created.status === 413 ? '空间不足' : '失败')
      return false
    }
    const id = created.data.id
    let offset = Number(created.data.offset) || 0
    const item = addQueue(created.data.name || file.name, file.size)
    updateQueue(item, offset, file.size)
    const chunk = 1024 * 1024
    while (offset < file.size) {
      const blob = file.slice(offset, Math.min(offset + chunk, file.size))
      let attempts = 0
      let progressed = false
      while (attempts < 3) {
        let response
        try {
          response = await api('PUT', `/api/fs/uploads/${id}`, blob, {
            'Content-Type': 'application/octet-stream',
            'Upload-Offset': String(offset),
          })
        } catch (error) {
          if (error.message === 'unauthorized') return false
          response = null
        }
        if (response && response.status === 200) {
          const data = await response.json()
          offset = Number(data.offset) || offset
          progressed = true
          break
        }
        if (response && response.status === 409) {
          const data = await response.json().catch(() => null)
          if (data && Number.isFinite(Number(data.offset))) offset = Number(data.offset)
          progressed = true
          break
        }
        if (response && (response.status === 507 || response.status === 413)) {
          finishQueue(item, '失败')
          toast('空间不足')
          return false
        }
        attempts++
        await sleep([2000, 5000, 15000][attempts - 1] || 15000)
      }
      if (!progressed) {
        finishQueue(item, '失败')
        toast('重试')
        return false
      }
      updateQueue(item, offset, file.size)
    }
    if (file.size === 0) {
      try {
        await api('PUT', `/api/fs/uploads/${id}`, new Blob([]), {
          'Content-Type': 'application/octet-stream',
          'Upload-Offset': '0',
        })
      } catch (_) {}
    }
    finishQueue(item, '已完成')
    return true
  }

  function conflictDialog() {
    return new Promise((resolve) => {
      showDialog('已有 1 个同名文件', '', [
        { label: '取消', className: 'ghost', run: async () => resolve('skip') },
        { label: '保留两者', className: 'ghost', run: async () => resolve('rename') },
        { label: '覆盖', className: 'primary', run: async () => resolve('overwrite') },
      ])
    })
  }

  async function uploadMany(files, options) {
    for (const file of files) {
      const relative = file.webkitRelativePath || ''
      let path = options.path || ''
      if (relative) {
        const pieces = relative.split('/')
        pieces.pop()
        for (const piece of pieces) {
          const target = path ? `${path}/${piece}` : piece
          try {
            await jsonApi('POST', '/api/fs/mkdir', { root: state.root, path, name: piece })
          } catch (_) {}
          path = target
        }
      }
      await uploadOne(file, Object.assign({}, options, { path }))
    }
    if (options.target !== 'push') await listDir(state.root, state.path)
    else await refreshTransfers()
  }

  // ------------------------------------------------------------------ push
  let transfersTimer = null
  function startTransfersPoll() {
    if (transfersTimer) return
    transfersTimer = setInterval(() => {
      if (!ui.shell.hidden && (state.view === 'push' || state.view === 'files')) refreshTransfers()
    }, 1200)
  }
  function stopTransfersPoll() {
    if (transfersTimer) clearInterval(transfersTimer)
    transfersTimer = null
  }

  async function refreshTransfers() {
    try {
      const result = await jsonApi('GET', '/api/transfers')
      state.transfers = Array.isArray(result.data) ? result.data : []
      renderPushList()
    } catch (_) {}
  }

  function stateText(transfer) {
    if (transfer.state === 'queued') return '等待中'
    if (transfer.state === 'active') return '上传中'
    if (transfer.state === 'pending') return '待处理'
    if (transfer.state === 'cancelled') return '已取消'
    if (transfer.state === 'failed') return '失败'
    if (transfer.state === 'done') {
      if (transfer.action === 'install') return '已安装'
      if (transfer.result === 'played') return '正在播放'
      if (transfer.result === 'shown') return '正在显示'
      return '已完成'
    }
    return '等待中'
  }

  function renderPushList() {
    ui.pushList.textContent = ''
    const pushes = state.transfers.filter((item) => item.kind === 'push' || item.kind === 'open')
    if (!pushes.length) {
      ui.pushList.innerHTML = `<div class="empty"><svg class="ico"><use href="/icons.svg#push"></use></svg><span>暂无传输</span></div>`
      return
    }
    for (const transfer of pushes) {
      const row = document.createElement('div')
      row.className = 'row'
      const thumb = document.createElement('div')
      thumb.className = 'thumb'
      thumb.innerHTML = `<svg class="ico"><use href="/icons.svg#${transfer.action === 'install' ? 'file' : transfer.action === 'view' ? 'image' : transfer.action === 'play' ? 'play' : 'push'}"></use></svg>`
      const name = document.createElement('div')
      name.className = 'name'
      const title = document.createElement('b')
      title.textContent = transfer.name
      const meta = document.createElement('span')
      const percent = transfer.total > 0 ? Math.round((transfer.bytes / transfer.total) * 100) : 0
      meta.textContent = `${fmtSize(transfer.total)} · ${transfer.state === 'active' ? `${percent}%` : ''}`
      name.append(title, meta)
      const status = document.createElement('div')
      status.className = 'dim'
      status.textContent = stateText(transfer)
      row.append(thumb, name, status)
      ui.pushList.append(row)
    }
  }

  // ------------------------------------------------------------------ desktop
  async function loadDesktop() {
    try {
      const result = await jsonApi('GET', '/api/desktop')
      const data = result.data || {}
      ui.tvName.textContent = data.device || ui.tvName.textContent
      ui.city.value = data.weatherCity || ''
      renderApps(Array.isArray(data.apps) ? data.apps : [])
    } catch (_) {}
  }

  function renderApps(apps) {
    ui.appList.textContent = ''
    for (const app of apps) {
      const row = document.createElement('div')
      row.className = 'app-row'
      const avatar = document.createElement('div')
      avatar.className = 'avatar'
      avatar.textContent = (app.label || '?').slice(0, 1)
      const label = document.createElement('div')
      label.className = 'label'
      const title = document.createElement('b')
      title.textContent = app.label || ''
      const component = document.createElement('span')
      component.textContent = app.component || ''
      label.append(title, component)
      const button = document.createElement('button')
      button.textContent = '显示名称'
      button.onclick = () => renameApp(app)
      row.append(avatar, label, button)
      ui.appList.append(row)
    }
  }

  function renameApp(app) {
    const field = input(app.label || '')
    showDialog('显示名称', field, [
      { label: '取消', className: 'ghost', run: async () => {} },
      {
        label: '保存',
        className: 'primary',
        run: async () => {
          const result = await jsonApi('POST', '/api/apps/label', {
            component: app.component,
            label: field.value.trim(),
          })
          if (result.status !== 200) toast('失败')
          else await loadDesktop()
        },
      },
    ])
  }

  async function uploadRaw(path, file, headers) {
    const response = await api('POST', path, file, Object.assign({
      'Content-Type': file.type || 'application/octet-stream',
    }, headers || {}))
    if (!response.ok) {
      toast(response.status === 415 ? '失败' : '失败')
      return false
    }
    return true
  }

  // ------------------------------------------------------------------ more
  async function loadMore() {
    try {
      const result = await jsonApi('GET', '/api/desktop/widgets')
      if (result.data && result.data.command) ui.grant.textContent = result.data.command
    } catch (_) {}
  }

  async function downloadDiagnostics() {
    const response = await api('GET', '/api/diag')
    if (!response.ok) return toast('失败')
    const blob = await response.blob()
    const link = document.createElement('a')
    link.href = URL.createObjectURL(blob)
    link.download = 'diagnostics.txt'
    document.body.append(link)
    link.click()
    link.remove()
    setTimeout(() => URL.revokeObjectURL(link.href), 5000)
  }

  async function copyGrant() {
    const text = ui.grant.textContent || ''
    try {
      await navigator.clipboard.writeText(text)
    } catch (_) {
      const area = document.createElement('textarea')
      area.value = text
      document.body.append(area)
      area.select()
      document.execCommand('copy')
      area.remove()
    }
    toast('已复制')
  }

  // ------------------------------------------------------------------ wiring
  ui.connect.onclick = () => {
    const code = ui.code.value.replace(/\s+/g, '')
    if (code.length !== 6) return
    login({ code })
  }
  ui.code.onkeydown = (event) => { if (event.key === 'Enter') ui.connect.click() }
  for (const button of ui.nav) {
    button.onclick = () => setView(button.dataset.view)
  }

  $('#uploadBtn').onclick = () => ui.fileInput.click()
  $('#uploadFolderBtn').onclick = () => ui.folderInput.click()
  ui.fileInput.onchange = async () => {
    if (ui.fileInput.files.length) {
      await uploadMany(Array.from(ui.fileInput.files), { root: state.root, path: state.path })
    }
    ui.fileInput.value = ''
  }
  ui.folderInput.onchange = async () => {
    if (ui.folderInput.files.length) {
      await uploadMany(Array.from(ui.folderInput.files), { root: state.root, path: state.path })
    }
    ui.folderInput.value = ''
  }
  $('#newFolder').onclick = newFolder
  $('#sortBtn').onclick = () => {
    state.sort = state.sort === 'name' ? 'size' : state.sort === 'size' ? 'time' : 'name'
    renderFiles()
  }
  $('#hiddenBtn').onclick = async () => {
    state.hidden = !state.hidden
    $('#hiddenBtn').classList.toggle('primary', state.hidden)
    await listDir(state.root, state.path)
  }
  $('#selectAll').onclick = () => {
    for (const file of state.files) state.selected.add(file.name)
    renderFiles()
    renderSelection()
  }
  $('#deleteSel').onclick = () => {
    const paths = Array.from(state.selected).map((name) => state.path ? `${state.path}/${name}` : name)
    deletePaths(paths)
  }
  $('#moveSel').onclick = () => {
    const paths = Array.from(state.selected).map((name) => state.path ? `${state.path}/${name}` : name)
    moveFile(paths)
  }

  $('#pushChoose').onclick = () => ui.pushInput.click()
  ui.pushInput.onchange = async () => {
    if (ui.pushInput.files.length) {
      await uploadMany(Array.from(ui.pushInput.files), {
        target: 'push',
        keep: $('#keepCopy').checked,
      })
    }
    ui.pushInput.value = ''
  }
  const drop = $('#dropZone')
  drop.ondragover = (event) => { event.preventDefault(); drop.classList.add('hot') }
  drop.ondragleave = () => drop.classList.remove('hot')
  drop.ondrop = async (event) => {
    event.preventDefault()
    drop.classList.remove('hot')
    if (event.dataTransfer.files.length) {
      await uploadMany(Array.from(event.dataTransfer.files), {
        target: 'push',
        keep: $('#keepCopy').checked,
      })
    }
  }

  $('#wallpaperChoose').onclick = () => ui.wallpaperInput.click()
  ui.wallpaperInput.onchange = async () => {
    const file = ui.wallpaperInput.files[0]
    if (file && await uploadRaw('/api/desktop/wallpaper', file)) {
      $('#wallpaperName').textContent = file.name
    }
    ui.wallpaperInput.value = ''
  }
  $('#clipChoose').onclick = () => ui.clipInput.click()
  ui.clipInput.onchange = async () => {
    const file = ui.clipInput.files[0]
    if (file && await uploadRaw('/api/clips', file, { 'X-Filename': file.name })) {
      $('#clipName').textContent = file.name
    }
    ui.clipInput.value = ''
  }
  $('#saveCity').onclick = async () => {
    const result = await jsonApi('POST', '/api/desktop/weather', { city: ui.city.value.trim() })
    if (result.status !== 200) toast('失败')
  }

  $('#diag').onclick = downloadDiagnostics
  $('#copyGrant').onclick = copyGrant
  $('#disconnect').onclick = disconnect
  $('#disconnectTop').onclick = disconnect
  ui.dialog.onclick = (event) => { if (event.target === ui.dialog) closeDialog() }

  async function disconnect() {
    try { await api('DELETE', '/api/session') } catch (_) {}
    logout('连接已断开')
  }

  async function boot() {
    startTransfersPoll()
    const hash = new URLSearchParams(location.hash.slice(1))
    const token = hash.get('t')
    if (token) await login({ token })
    else {
      ui.loginCode.hidden = false
      ui.loginWait.hidden = true
    }
  }

  boot()
})()
