import http from 'node:http'

// * Synthetic signing endpoint for the Spring/React record E2E; it never stores a PDF.
let signed = 0
http.createServer((req, res) => {
  if (req.method === 'GET' && req.url === '/counts') {
    res.setHeader('Content-Type', 'application/json')
    return res.end(JSON.stringify({ signed }))
  }
  const path = req.url?.slice('/storage/v1/object/sign/materials/'.length)
  if (req.method !== 'POST' || !req.url?.startsWith('/storage/v1/object/sign/materials/')
      || !/^users\/[A-Za-z0-9-]+\/materials\/[A-Za-z0-9-]+\.pdf$/.test(path)
      || req.headers.authorization !== 'Bearer synthetic-record-only') {
    res.writeHead(404)
    return res.end()
  }
  signed++
  res.setHeader('Content-Type', 'application/json')
  res.end(JSON.stringify({ signedURL: `/object/sign/materials/${path}?token=synthetic-record-only` }))
}).listen(18085, '127.0.0.1')
