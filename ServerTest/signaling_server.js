const WebSocket = require('ws');

const PORT = process.env.PORT || 8080;

// Khởi tạo WebSocket server
const wss = new WebSocket.Server({ port: PORT });

// Lưu trữ tất cả các client đang kết nối, sử dụng Map với client_id làm key
const clients = new Map();

console.log(`Signaling server started on ws://localhost:${PORT}`);

wss.on('connection', (ws, req) => {
    // Lấy client_id từ URL, ví dụ: /some_client_id
    const urlParts = req.url.split('/');
    const clientId = urlParts[urlParts.length - 1];

    if (!clientId) {
        console.log('Client connected without clientId, closing connection.');
        ws.close(1008, 'Client ID is required'); // 1008: Policy Violation
        return;
    }

    if (clients.has(clientId)) {
        console.log(`Client with ID '${clientId}' already connected. Closing new connection.`);
        ws.close(1008, `Client ID '${clientId}' is already in use.`);
        return;
    }

    // Lưu client_id vào đối tượng ws để dễ truy cập sau này
    ws.clientId = clientId;
    clients.set(clientId, ws);
    console.log(`Client '${clientId}' connected. Total clients: ${clients.size}`);

    ws.on('message', (messageAsString) => {
        const message = messageAsString.toString(); // Đảm bảo message là string
        console.log(`Received message from '${ws.clientId}' =>`, message);

        // Broadcast message đến tất cả các client *khác*
        clients.forEach((client, id) => {
            if (id !== ws.clientId && client.readyState === WebSocket.OPEN) {
                try {
                    const messageWithClientId = JSON.stringify({
                        clientId: ws.clientId,
                        message: message
                    });
                    client.send(messageWithClientId);
                } catch (error) {
                    console.error(`Error sending message to client '${id}':`, error);
                    // clients.delete(id); // Cân nhắc xóa client nếu lỗi
                }
            }
        });
    });

    ws.on('close', () => {
        clients.delete(ws.clientId);
        console.log(`Client '${ws.clientId}' disconnected. Total clients: ${clients.size}`);
    });

    ws.on('error', (error) => {
        console.error(`WebSocket error on client '${ws.clientId}':`, error);
        clients.delete(ws.clientId); // Đảm bảo client được xóa nếu có lỗi
        console.log(`Client '${ws.clientId}' removed due to error. Total clients: ${clients.size}`);
    });
});

// (Tùy chọn) Xử lý lỗi chung của server
wss.on('error', (error) => {
    console.error('WebSocket Server error:', error);
});

console.log('Waiting for connections...');
