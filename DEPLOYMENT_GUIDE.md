# ShareBite Deployment Guide

## Overview
ShareBite is a comprehensive food donation platform with Spring Boot backend, React frontend, and Python ML services. This guide provides deployment options for production environments.

## Prerequisites
- Java 17+ (for Spring Boot)
- Node.js 16+ (for React build)
- Python 3.8+ (for ML services)
- MySQL 8.0+ (database)
- Docker & Docker Compose (recommended)
- Nginx (for reverse proxy)

## Architecture
```
Internet → Nginx (SSL/TLS) → Spring Boot (API + WebSocket)
                          → React SPA (served statically)
                          → Python ML Service (FastAPI)
                          → MySQL Database
```

## Deployment Options

### Option 1: Free Tier - Railway + Vercel (Recommended for MVP)

#### Backend (Railway)
1. **Railway Account**: Sign up at [railway.app](https://railway.app)
2. **Database**: Use Railway's built-in MySQL (free tier: 512MB)
3. **Deploy Spring Boot**:
   ```bash
   # Connect GitHub repo
   # Railway auto-detects Java/Spring Boot
   # Set environment variables in Railway dashboard
   ```
4. **Environment Variables**:
   ```
   JWT_SECRET=your-secure-jwt-secret-here
   GOOGLE_MAPS_API_KEY=your-google-maps-key
   SPRING_PROFILES_ACTIVE=prod
   ```

#### Frontend (Vercel)
1. **Vercel Account**: Sign up at [vercel.com](https://vercel.com)
2. **Connect Repository**: Import your GitHub repo
3. **Build Settings**:
   - Build Command: `npm run build`
   - Output Directory: `build`
   - Install Command: `npm install`
4. **Environment Variables**:
   ```
   REACT_APP_API_BASE_URL=https://your-railway-app.railway.app/api
   REACT_APP_WS_URL=wss://your-railway-app.railway.app/ws
   ```

#### ML Service (Railway)
1. Deploy Python service to Railway
2. Use Railway's persistent storage for ML models

**Cost**: ~$0/month (Railway free tier + Vercel free tier)
**Pros**: Easy setup, auto-scaling, managed infrastructure
**Cons**: Limited resources, vendor lock-in

### Option 2: Paid Cloud - AWS/GCP/Azure

#### AWS Deployment (Production Ready)
1. **EC2/ECS**: Containerized deployment
2. **RDS**: Managed MySQL database
3. **S3**: File storage for uploads
4. **CloudFront**: CDN for static assets
5. **API Gateway**: For API management
6. **Route 53**: DNS management

#### GCP Deployment
1. **Cloud Run**: Serverless containers
2. **Cloud SQL**: Managed MySQL
3. **Cloud Storage**: File storage
4. **Cloud Build**: CI/CD pipelines

#### Azure Deployment
1. **App Service**: Web app hosting
2. **Azure Database**: MySQL hosting
3. **Blob Storage**: File storage
4. **DevOps**: CI/CD pipelines

**Cost**: $50-200/month depending on traffic
**Pros**: Scalable, enterprise features, monitoring
**Cons**: Complex setup, higher costs

### Option 3: Self-Hosted - VPS/Dedicated Server

#### DigitalOcean Droplet
1. **Ubuntu 22.04 LTS** VPS ($12/month)
2. **Install Dependencies**:
   ```bash
   # Update system
   sudo apt update && sudo apt upgrade

   # Install Java
   sudo apt install openjdk-17-jdk

   # Install Node.js
   curl -fsSL https://deb.nodesource.com/setup_18.x | sudo -E bash -
   sudo apt-get install -y nodejs

   # Install MySQL
   sudo apt install mysql-server-8.0
   sudo mysql_secure_installation

   # Install Nginx
   sudo apt install nginx
   ```

3. **Configure MySQL**:
   ```sql
   CREATE DATABASE bitesharing;
   CREATE USER 'sharebite'@'localhost' IDENTIFIED BY 'secure-password';
   GRANT ALL PRIVILEGES ON bitesharing.* TO 'sharebite'@'localhost';
   FLUSH PRIVILEGES;
   ```

4. **Deploy Backend**:
   ```bash
   # Build JAR
   ./mvnw clean package -DskipTests

   # Create systemd service
   sudo nano /etc/systemd/system/sharebite.service
   ```

5. **Systemd Service**:
   ```ini
   [Unit]
   Description=ShareBite Spring Boot App
   After=network.target

   [Service]
   User=ubuntu
   WorkingDirectory=/home/ubuntu/sharebite
   ExecStart=/usr/bin/java -jar target/*.jar
   SuccessExitStatus=143
   Restart=always

   [Install]
   WantedBy=multi-user.target
   ```

6. **Deploy Frontend**:
   ```bash
   npm run build
   sudo cp -r build/* /var/www/html/
   ```

7. **Configure Nginx**:
   ```nginx
   server {
       listen 80;
       server_name your-domain.com;

       # Frontend
       location / {
           root /var/www/html;
           try_files $uri $uri/ /index.html;
       }

       # Backend API
       location /api/ {
           proxy_pass http://localhost:8080;
           proxy_set_header Host $host;
           proxy_set_header X-Real-IP $remote_addr;
       }

       # WebSocket
       location /ws/ {
           proxy_pass http://localhost:8080;
           proxy_http_version 1.1;
           proxy_set_header Upgrade $http_upgrade;
           proxy_set_header Connection "upgrade";
       }
   }
   ```

**Cost**: $12-50/month
**Pros**: Full control, cost-effective
**Cons**: Manual maintenance, security responsibility

## SSL Certificate (Let's Encrypt)

```bash
# Install Certbot
sudo apt install certbot python3-certbot-nginx

# Get SSL certificate
sudo certbot --nginx -d your-domain.com

# Auto-renewal (runs twice daily)
sudo crontab -e
# Add: 0 12 * * * /usr/bin/certbot renew --quiet
```

## Monitoring & Maintenance

### Health Checks
- Spring Boot Actuator: `/actuator/health`
- Database connectivity monitoring
- File upload space monitoring

### Backup Strategy
```bash
# Database backup script
mysqldump -u sharebite -p bitesharing > backup_$(date +%Y%m%d).sql

# File backup
tar -czf uploads_backup_$(date +%Y%m%d).tar.gz uploads/
```

### Scaling Considerations
- **Horizontal Scaling**: Multiple app instances behind load balancer
- **Database**: Read replicas for high traffic
- **Caching**: Redis for session/API caching
- **CDN**: CloudFlare for global distribution

## Environment Variables Summary

```bash
# Backend
JWT_SECRET=your-secure-jwt-secret
GOOGLE_MAPS_API_KEY=your-google-maps-key
SPRING_PROFILES_ACTIVE=prod
DATABASE_URL=jdbc:mysql://localhost:3306/bitesharing
DATABASE_USERNAME=sharebite
DATABASE_PASSWORD=secure-password

# Frontend
REACT_APP_API_BASE_URL=https://your-domain.com/api
REACT_APP_WS_URL=wss://your-domain.com/ws
REACT_APP_GOOGLE_MAPS_API_KEY=your-google-maps-key

# ML Service
MODEL_PATH=/path/to/models
UPLOAD_DIR=/path/to/uploads
```

## Troubleshooting

### Common Issues
1. **Port conflicts**: Check if ports 80, 443, 8080 are available
2. **Database connection**: Verify MySQL credentials and network access
3. **File permissions**: Ensure upload directory is writable
4. **SSL issues**: Check certificate validity and Nginx configuration

### Logs
```bash
# Spring Boot logs
journalctl -u sharebite -f

# Nginx logs
sudo tail -f /var/log/nginx/error.log
sudo tail -f /var/log/nginx/access.log

# MySQL logs
sudo tail -f /var/log/mysql/error.log
```

## Performance Optimization

1. **Database Indexing**: Ensure proper indexes on frequently queried columns
2. **Caching**: Implement Redis for session and API response caching
3. **Image Optimization**: Use WebP format and responsive images
4. **CDN**: Serve static assets from CDN
5. **Database Connection Pooling**: Configure HikariCP properly

## Security Checklist

- [ ] SSL/TLS enabled
- [ ] JWT tokens with secure secrets
- [ ] Database credentials encrypted
- [ ] File upload validation
- [ ] CORS properly configured
- [ ] Security headers (CSP, HSTS)
- [ ] Regular security updates
- [ ] Firewall configured
- [ ] Backup encryption

## Next Steps

1. Choose deployment option based on budget and requirements
2. Set up CI/CD pipeline for automated deployments
3. Configure monitoring and alerting
4. Set up backup and disaster recovery
5. Plan for scaling as user base grows

For detailed setup instructions, refer to the README.md files in each service directory.