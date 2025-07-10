/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: true,
  swcMinify: true,
  output: 'export', // Enable static export for Next.js 14+
  async rewrites() {
    return [
      {
        source: '/api/upload',
        destination: 'http://backend:8080/upload', // Use Docker service name for backend
      },
      {
        source: '/api/download/:port',
        destination: 'http://backend:8080/download/:port', // Use Docker service name for backend
      },
    ];
  },
}

module.exports = nextConfig
