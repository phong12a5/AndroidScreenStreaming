const WebSocket = require('ws');

const PORT = process.env.PORT || 8080;

// Khởi tạo WebSocket server
const wss = new WebSocket.Server({ port: PORT });

// Lưu trữ tất cả các client đang kết nối
const clients = new Set();

console.log(`Signaling server started on ws://localhost:${PORT}`);

wss.on('connection', (ws) => {
    clients.add(ws);
    console.log('Client connected. Total clients:', clients.size);

    ws.on('message', (messageAsString) => {
        const message = messageAsString.toString(); // Đảm bảo message là string
        console.log('Received message =>', message);

        // Broadcast message đến tất cả các client *khác*
        clients.forEach((client) => {
            if (client !== ws && client.readyState === WebSocket.OPEN) {
                try {
                    client.send(message);
                } catch (error) {
                    console.error('Error sending message to client:', error);
                    // Có thể xóa client này nếu gửi lỗi nhiều lần
                    // clients.delete(client);
                }
            }
        });
    });

    ws.on('close', () => {
        clients.delete(ws);
        console.log('Client disconnected. Total clients:', clients.size);
    });

    ws.on('error', (error) => {
        console.error('WebSocket error on client:', error);
        // Đảm bảo client được xóa nếu có lỗi
        clients.delete(ws);
    });
});

// (Tùy chọn) Xử lý lỗi chung của server
wss.on('error', (error) => {
    console.error('WebSocket Server error:', error);
});

console.log('Waiting for connections...');
