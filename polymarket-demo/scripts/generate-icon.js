const { createCanvas } = require('canvas');
const fs = require('fs');
const path = require('path');

// 创建 512x512 的画布
const canvas = createCanvas(512, 512);
const ctx = canvas.getContext('2d');

// 设置背景色（标题背景色 #001529）
ctx.fillStyle = '#001529';
ctx.fillRect(0, 0, 512, 512);

// 设置图标颜色（深色背景使用较亮的颜色）
const gradientColors = {
  start: '#69c0ff',
  end: '#b37feb'
};

// 创建渐变
const gradient = ctx.createLinearGradient(0, 0, 512, 512);
gradient.addColorStop(0, gradientColors.start);
gradient.addColorStop(1, gradientColors.end);

// 计算缩放比例（原始 viewBox 是 64x64，需要缩放到 512x512）
const scale = 512 / 64;
const centerX = 256;
const centerY = 256;

// 保存当前状态
ctx.save();

// 移动到中心并缩放
ctx.translate(centerX, centerY);
ctx.scale(scale, scale);
ctx.translate(-32, -32); // 偏移到原点

// 绘制左侧箭头（指向中心）
ctx.beginPath();
ctx.moveTo(16, 32);
ctx.lineTo(8, 24);
ctx.lineTo(8, 40);
ctx.closePath();
ctx.fillStyle = gradient;
ctx.fill();

// 绘制中心连接线
ctx.beginPath();
ctx.moveTo(20, 32);
ctx.lineTo(44, 32);
ctx.strokeStyle = gradient;
ctx.lineWidth = 3 / scale; // 调整线宽
ctx.lineCap = 'round';
ctx.stroke();

// 绘制右侧箭头（指向中心）
ctx.beginPath();
ctx.moveTo(48, 32);
ctx.lineTo(56, 24);
ctx.lineTo(56, 40);
ctx.closePath();
ctx.fillStyle = gradient;
ctx.fill();

// 绘制中心圆点
ctx.beginPath();
ctx.arc(32, 32, 5, 0, Math.PI * 2);
ctx.fillStyle = gradient;
ctx.fill();

// 绘制装饰性数据流弧线（上方）
ctx.beginPath();
ctx.moveTo(20, 20);
ctx.quadraticCurveTo(32, 14, 44, 20);
ctx.strokeStyle = gradient;
ctx.lineWidth = 2 / scale;
ctx.globalAlpha = 0.5;
ctx.lineCap = 'round';
ctx.stroke();

// 绘制装饰性数据流弧线（下方）
ctx.beginPath();
ctx.moveTo(20, 44);
ctx.quadraticCurveTo(32, 50, 44, 44);
ctx.strokeStyle = gradient;
ctx.lineWidth = 2 / scale;
ctx.globalAlpha = 0.5;
ctx.lineCap = 'round';
ctx.stroke();

// 恢复状态
ctx.restore();

// 保存为 PNG（输出到当前目录）
const outputPath = path.join(__dirname, '../icon-512x512.png');
// 确保输出目录存在
const outputDir = path.dirname(outputPath);
if (!fs.existsSync(outputDir)) {
  fs.mkdirSync(outputDir, { recursive: true });
}

const buffer = canvas.toBuffer('image/png');
fs.writeFileSync(outputPath, buffer);

console.log(`✅ 图标已生成: ${outputPath}`);
console.log(`   尺寸: 512x512 像素`);
console.log(`   背景色: #001529`);

