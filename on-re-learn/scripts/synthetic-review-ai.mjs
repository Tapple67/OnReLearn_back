import http from 'node:http'

// * Local Responses fixture for the real Spring/React weak-topic and general quiz E2E.
// ! Never log request bodies or headers; use only synthetic accounts with this server.
const general = process.argv.includes('--general')
const wrongReview = process.argv.includes('--wrong-review')
const counts = { summary: 0, quiz: 0, weakTopic: 0, general: 0, wrongReview: 0 }
const items = Array.from({ length: 10 }, (_, index) => {
  const type = index < (general ? 6 : 5) ? 'mcq' : index < (general ? 9 : 8) ? 'tf' : 'short'
  return {
    type, question: `합성 분수 문제 ${index + 1}`, explanation: '분수는 전체에 대한 부분입니다.',
    topic: '분수', evidence: { source: 'pdf', page: 1, quote: '분수는 전체를 같은 크기로 나눈 부분을 나타낸다.' },
    ...(type === 'mcq' ? { choices: ['부분', '전체만'], answerKey: { correctIndex: 0 } }
      : type === 'tf' ? { answerKey: { correct: true } } : { answerKey: { accepted: ['분수'] } }),
  }
})
http.createServer(async (req, res) => {
  res.setHeader('Content-Type', 'application/json')
  if (req.method === 'GET' && req.url === '/counts') return res.end(JSON.stringify(counts))
  if (req.method !== 'POST' || !['/summary', '/quiz'].includes(req.url)) {
    res.writeHead(404); return res.end('{}')
  }
  try {
    let raw = ''
    for await (const chunk of req) raw += chunk
    const request = JSON.parse(raw)
    const kind = req.url.slice(1)
    counts[kind]++
    if (kind === 'quiz' && !general && !wrongReview && String(request.input).includes('분수')) counts.weakTopic++
    if (kind === 'quiz' && general) counts.general++
    if (kind === 'quiz' && wrongReview) counts.wrongReview++
    const text = kind === 'summary' ? '# 분수\n분수는 전체에 대한 부분입니다.'
      : JSON.stringify({ title: general ? '합성 분수 일반 퀴즈'
        : wrongReview ? '합성 분수 오답 복습' : '합성 분수 약점 복습', items })
    res.end(JSON.stringify({ status: 'completed', output: [{ type: 'message', role: 'assistant',
      status: 'completed', content: [{ type: 'output_text', text }] }] }))
  } catch {
    res.writeHead(400); res.end('{}')
  }
}).listen(18084, '127.0.0.1')
